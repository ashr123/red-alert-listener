package io.github.ashr123.red.alert;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.ashr123.option.Option;
import io.github.ashr123.option.Some;
import io.github.ashr123.timeMeasurement.Result;
import io.github.ashr123.timeMeasurement.TimeMeasurement;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.util.datetime.FixedDateFormat;
import picocli.CommandLine;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@CommandLine.Command(name = "java -jar red-alert-listener.jar",
		mixinStandardHelpOptions = true,
		versionProvider = Listener.class,
		showDefaultValues = true,
		description = "An App that can get \"red alert\"s from IDF's Home Front Command.")
public class Listener implements Runnable, CommandLine.IVersionProvider {
	private static final TypeReference<List<District>> LIST_TYPE_REFERENCE = new TypeReference<>() {
	};
	private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(FixedDateFormat.FixedFormat.DEFAULT.getPattern(), Locale.getDefault(Locale.Category.FORMAT)).withZone(ZoneId.systemDefault());
	private static final Logger LOGGER = LogManager.getLogger();
	private static final ObjectMapper JSON_MAPPER = new JsonMapper()
			.enable(SerializationFeature.INDENT_OUTPUT)
			.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
//			.disable(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS)
			.findAndRegisterModules();
	private static final Configuration DEFAULT_CONFIGURATION = new Configuration(
			false,
			false,
			true,
			true,
			false,
			Duration.ofSeconds(10),
			LanguageCode.HE,
			Level.INFO,
			Collections.emptySet()
	);
	private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
	//		private static final Pattern
//			VAR_ALL_DISTRICTS = Pattern.compile("^.*=\\s*", Pattern.MULTILINE),
//			BOM = Pattern.compile("﻿");
	private static final Collator COLLATOR = Collator.getInstance(Locale.ROOT);
	@CommandLine.Option(names = {"-c", "--configuration-file"},
			paramLabel = "configuration file",
			defaultValue = "red-alert-listener.conf.json",
			description = "Enter custom path to configuration file.")
	private File configurationFile;
	private volatile boolean isContinue = true;
	private Configuration configuration = DEFAULT_CONFIGURATION;
	private long configurationLastModified = 1;
	private List<String> districtsNotFound = Collections.emptyList();
	/**
	 * Will be updated once a day from IDF's Home Front Command's server.
	 */
	private volatile Map<String, AreaTranslationProtectionTime> districts;

	private Listener() {
	}

	public static void main(String... args) {
		new CommandLine(Listener.class)
				.setCaseInsensitiveEnumValuesAllowed(true)
				.execute(args);
	}

	private static void setLoggerLevel(Level level) {
		final LoggerContext loggerContext = (LoggerContext) LogManager.getContext(false);
		loggerContext.getConfiguration().getLoggerConfig(LOGGER.getName()).setLevel(level);
		loggerContext.updateLoggers();
	}

	private static void printHelpMsg() {
		System.err.println("Enter \"t\" for sound test, \"c\" for clearing the screen, \"r\" for refresh the districts translation dictionary, \"q\" to quit or \"h\" for displaying this help massage.");
	}

	private static void sleep() {
		try {
			Thread.sleep(1000);
		} catch (InterruptedException interruptedException) {
			//noinspection CallToPrintStackTrace
			interruptedException.printStackTrace(); // TODO think about
		}
	}

	private static Set<String> getTranslations(Collection<IAreaTranslationProtectionTime> translatedData) {
		return translatedData.parallelStream().unordered()
				.map(IAreaTranslationProtectionTime::translation)
				.collect(Collectors.toSet());
	}

	private String areaAndTranslatedDistrictsToString(CharSequence headline,
													  Map<String, List<AreaTranslationProtectionTime>> districtsByAreaName,
													  int cat) {
		final Function<AreaTranslationProtectionTime, String> toString = cat == 1 || cat == 101 ?
				areaTranslationProtectionTime -> areaTranslationProtectionTime.translation() + " (" + configuration.languageCode().getTimeTranslation(areaTranslationProtectionTime.protectionTimeInSeconds()) + ")" :
				AreaTranslationProtectionTime::translation;
		return districtsByAreaName.entrySet().parallelStream().unordered()
				.sorted(Map.Entry.comparingByKey())
				.map(areaNameAndDistricts -> areaNameAndDistricts.getValue().parallelStream().unordered()
						.map(toString)
						.sorted()
						.collect(Collectors.joining(
								"," + System.lineSeparator() + "\t\t",
								areaNameAndDistricts.getKey() + ":" + System.lineSeparator() + "\t\t",
								""
						)))
				.collect(Collectors.joining(
						"," + System.lineSeparator() + "\t",
						headline + ":" + System.lineSeparator() + "\t",
						System.lineSeparator()
				));
	}

	@CommandLine.Command(name = "get-remote-districts-as-json",
			mixinStandardHelpOptions = true,
			versionProvider = Listener.class,
			showDefaultValues = true,
			description = "Gets all supported districts translation from Hebrew to the appropriate translation from IDF's Home Front Command's server and print it to stdout (No need for configuration file).")
	private void getRemoteDistrictsAsJSON(
			@CommandLine.Option(names = {"-l", "--language"},
					paramLabel = "language code",
					required = true,
					description = "Which language's translation to get? Valid values: ${COMPLETION-CANDIDATES} (case insensitive).")
			LanguageCode languageCode,
			@CommandLine.Option(names = {"-t", "--timeout"},
					paramLabel = "timeout",
					defaultValue = "PT10S",
					description = "Timeout for connecting to IDF's Home Front Command's server in ISO 8601 (Duration) format, see https://en.wikipedia.org/wiki/ISO_8601#Durations.")
			Duration timeout,
			@CommandLine.Option(names = {"-L", "--logger-level"},
					paramLabel = "logger level",
					defaultValue = "INFO",
					converter = LoggerLevelConverter.class,
					description = "Level of logger. Valid values: OFF, FATAL, ERROR, WARN, INFO, DEBUG, TRACE, ALL (case insensitive).")
			Level loggerLevel
	) throws IOException, InterruptedException {
		System.out.println(JSON_MAPPER.writeValueAsString(startSubcommandInputThread(
				languageCode,
				timeout,
				loggerLevel,
				District::label
		)));
	}

	@CommandLine.Command(name = "get-remote-districts-as-json-to-file",
			mixinStandardHelpOptions = true,
			versionProvider = Listener.class,
			showDefaultValues = true,
			description = "Gets all supported districts translation from Hebrew from IDF's Home Front Command's server and print it to file, grouped by area-name, including also protection time in seconds (No need for configuration file).")
	private void getRemoteDistrictsAsJSONToFile(
			@CommandLine.Option(names = {"-o", "--output"},
					paramLabel = "file",
					defaultValue = "districts.json",
					description = "Where to save received districts.")
			File file,
			@CommandLine.Option(names = {"-l", "--language"},
					paramLabel = "language code",
					required = true,
					description = "Which language's translation to get? Valid values: ${COMPLETION-CANDIDATES} (case insensitive).")
			LanguageCode languageCode,
			@CommandLine.Option(names = {"-t", "--timeout"},
					paramLabel = "timeout",
					defaultValue = "PT10S",
					description = "Timeout for connecting to IDF's Home Front Command's server in ISO 8601 (Duration) format, see https://en.wikipedia.org/wiki/ISO_8601#Durations.")
			Duration timeout,
			@CommandLine.Option(names = {"-L", "--logger-level"},
					paramLabel = "logger level",
					defaultValue = "INFO",
					converter = LoggerLevelConverter.class,
					description = "Level of logger. Valid values: OFF, FATAL, ERROR, WARN, INFO, DEBUG, TRACE, ALL (case insensitive).")
			Level loggerLevel
	) throws IOException, InterruptedException {
		JSON_MAPPER.writeValue(
				file,
				startSubcommandInputThread(
						languageCode,
						timeout,
						loggerLevel,
						district -> new AreaTranslationProtectionTime(
								district.areaname(),
								district.label(),
								district.migun_time()
						)
				)
		);
	}

	private <D> Map<String, D> startSubcommandInputThread(LanguageCode languageCode,
														  Duration timeout,
														  Level level,
														  Function<District, D> districtMapper) throws InterruptedException {
		final CountDownLatch startSignal = new CountDownLatch(1);
		Thread.startVirtualThread(() -> {
			try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
				System.err.println("Enter \"q\" to quit");
				startSignal.countDown();
				while (isContinue)
					switch (scanner.nextLine().strip()) {
						case "" -> {
						}
						case "q" -> {
							System.err.println("Quiting...");
							isContinue = false;
						}
						default -> System.err.println("""
								Unrecognized command!
								Enter "q" to quit""");
					}
			} catch (NoSuchElementException ignored) {
			}
		});
		setLoggerLevel(level);
		startSignal.await();
		return loadRemoteDistricts(languageCode, timeout, districtMapper);
	}

	private <T> Map<String, T> loadRemoteDistricts(LanguageCode languageCode,
												   Duration timeout,
												   Function<District, T> districtMapper) {
		LOGGER.info("Getting districts from IDF's Home Front Command's server...");
		while (isContinue) {
			try {
				final Result<Map<String, T>> result = TimeMeasurement.measureAndExecuteCallable(() -> {
					final HttpResponse<InputStream> httpResponse = HTTP_CLIENT.send(
							HttpRequest.newBuilder(URI.create("https://www.oref.org.il/Shared/Ajax/GetDistricts.aspx?lang=" + languageCode.name().toLowerCase(Locale.ROOT)))
									.header("Accept", "application/json")
									.timeout(timeout)
									.build(),
							HttpResponse.BodyHandlers.ofInputStream()
					);
					try (InputStream body = httpResponse.body()) {
						if (httpResponse.statusCode() >= 200 && httpResponse.statusCode() < 300)
							return JSON_MAPPER.readValue(
											/*VAR_ALL_DISTRICTS.matcher(httpResponse.*/body/*()).replaceFirst("")*/,
											LIST_TYPE_REFERENCE
									)
									.parallelStream().unordered()
									.collect(Collectors.toMap(
											District::label_he,
											districtMapper,
											(value1, value2) -> {
												LOGGER.trace("value1: {}, value2: {}", value1, value2);
												return value2;
											}
									));
					}
					LOGGER.error("Got bad response status code: {}", httpResponse.statusCode());
					return Collections.emptyMap();
				});
				if (result.getResult().isEmpty()) {
					sleep();
					continue;
				}
				LOGGER.info("Done (took {} milliseconds, got {} districts)", result.getTimeTaken(), result.getResult().size());
				return result.getResult();
			} catch (JsonParseException e) {
				LOGGER.error("JSON parsing error: {}", e.toString());
			} catch (Exception e) {
				LOGGER.debug("Failed to get data for language code {}: {}. Trying again...", languageCode, e.toString());
			}
			sleep();
		}
		return Collections.emptyMap();
	}

	private void printDistrictsNotFoundWarning() {
		if (!districtsNotFound.isEmpty())
			LOGGER.warn("Those districts don't exist: {}", districtsNotFound);
	}

	@Override
	public String[] getVersion() {
		return new String[]{"Red Alert Listener v" + getClass().getPackage().getImplementationVersion()};
	}

	private void loadConfiguration() throws IOException {
		final long configurationLastModifiedTemp = configurationFile.lastModified();
		final LanguageCode oldLanguageCode = configuration.languageCode();
		if (configurationLastModifiedTemp > configurationLastModified) {
			LOGGER.info("(Re)Loading configuration from file \"{}\"", configurationFile);
			configuration = JSON_MAPPER.readValue(configurationFile, Configuration.class);
			configurationLastModified = configurationLastModifiedTemp;
			if (districts == null || !oldLanguageCode.equals(configuration.languageCode()))
				refreshDistrictsTranslation();
			districtsNotFound = (configuration.districtsOfInterest().size() > 2 ?
					new ArrayList<>(configuration.districtsOfInterest()) :
					configuration.districtsOfInterest()).parallelStream().unordered()
					.filter(Predicate.not(getTranslations(new ArrayList<>(districts.values()))::contains))
					.toList();
			printDistrictsNotFoundWarning();
			setLoggerLevel(configuration.logLevel());
		} else if (configurationLastModifiedTemp == 0 && configurationLastModified != 0) {
			LOGGER.warn("couldn't find \"{}\", using default configuration", configurationFile);
			configuration = DEFAULT_CONFIGURATION;
			if (districts == null || !oldLanguageCode.equals(configuration.languageCode()))
				refreshDistrictsTranslation();
			configurationLastModified = 0;
			districtsNotFound = Collections.emptyList();
			setLoggerLevel(configuration.logLevel());
		}
	}

	@Override
	public void run() {
		System.err.println("Preparing Red Alert Listener v" + getClass().getPackage().getImplementationVersion() + "...");
		try (Clip clip = AudioSystem.getClip(/*Stream.of(AudioSystem.getMixerInfo()).parallel().unordered()
				.filter(mixerInfo -> COLLATOR.equals(mixerInfo.getName(), "default [default]"))
				.findAny()
				.orElse(null)*/);
			 AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(new BufferedInputStream(Objects.requireNonNull(getClass().getResourceAsStream("/alarmSound.wav"))));
			 ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor()) {
			clip.open(audioInputStream);
			Thread.startVirtualThread(() -> {
				try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
					printHelpMsg();
					while (isContinue)
						switch (scanner.nextLine().strip()) {
							case "" -> {
							}
							case "q", "quit", "exit" -> isContinue = false;
							case "t", "test", "test-sound" -> {
								System.err.println("Testing sound...");
								clip.setFramePosition(0);
								clip.start();
							}
							case "c", "clear" -> System.err.println("\033[H\033[2JListening...");
							case "r", "refresh", "refresh-districts" -> refreshDistrictsTranslation();
							case "h", "help" -> printHelpMsg();
							default -> {
								System.err.println("Unrecognized command!");
								printHelpMsg();
							}
						}
				} catch (NoSuchElementException ignored) {
				}
				System.err.println("Bye Bye!");
			});
			scheduledExecutorService.scheduleAtFixedRate(this::refreshDistrictsTranslation, 1, 1, TimeUnit.DAYS);
			loadConfiguration();
			final URI uri = URI.create("https://www.oref.org.il/WarningMessages/alert/alerts.json");
			Map<Integer, Set<String>> prevData = new HashMap<>();
			Instant currAlertsLastModified = Instant.MIN;
			final int minRedAlertEventContentLength = """
					{"cat":"1","data":[],"desc":"","id":0,"title":""}""".getBytes(StandardCharsets.UTF_8).length;
			final double alarmSoundSecondLength = clip.getMicrosecondLength() / 1E6;
			System.err.println("Listening...");
			while (isContinue)
				try {
					loadConfiguration();
					final HttpResponse<InputStream> httpResponse = HTTP_CLIENT.send(
							HttpRequest.newBuilder(uri)
									.header("Accept", "application/json")
									.header("X-Requested-With", "XMLHttpRequest")
									.header("Referer", "https://www.oref.org.il/12481-" + configuration.languageCode().name().toLowerCase(Locale.ROOT) + "/Pakar.aspx")
									.timeout(configuration.timeout())
									.build(),
							HttpResponse.BodyHandlers.ofInputStream()
					);

					try (InputStream body = httpResponse.body()) {
						if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
							LOGGER.error("Connection response status code: {}", httpResponse.statusCode());
							sleep();
							continue;
						}
						final Instant alertsLastModified;
						final long contentLength = httpResponse.headers().firstValueAsLong("Content-Length").orElse(-1);
						if (contentLength < minRedAlertEventContentLength)
							prevData.clear();
						else if ((alertsLastModified = httpResponse.headers().firstValue("Last-Modified")
								.map(lastModifiedStr -> DateTimeFormatter.RFC_1123_DATE_TIME.parse(lastModifiedStr, Instant::from))
								.filter(currAlertsLastModified::isBefore)
								.orElse(null)) != null) {
							currAlertsLastModified = alertsLastModified;

							final RedAlertEvent redAlertEvent = JSON_MAPPER.readValue(
									/*BOM.matcher(httpResponse.*/body/*()).replaceFirst("")*/,
									RedAlertEvent.class
							);
							LOGGER.debug("Original event data: {}", redAlertEvent);
							// TODO rethink of what defines a drill alert
							if (redAlertEvent.data().parallelStream().unordered()
									.allMatch(LanguageCode.HE::containsTestKey)) {
								if (configuration.isShowTestAlerts())
									System.out.println(redAlertToString(
											contentLength,
											currAlertsLastModified,
											redAlertEvent,
											redAlertEvent.data().parallelStream().unordered()
													.map(configuration.languageCode()::getTestTranslation)
													.sorted()
													.collect(Collectors.joining(
															"," + System.lineSeparator() + "\t",
															"Test Alert:" + System.lineSeparator() + "\t",
															System.lineSeparator()
													)),
											new StringBuilder()
									));
								continue;
							}

							List<? extends IAreaTranslationProtectionTime> translatedData = filterPrevAndGetTranslatedData(redAlertEvent, prevData);

							boolean isContainsMissingTranslations = translatedData.parallelStream().unordered().anyMatch(MissingAreaTranslationProtectionTime.class::isInstance);
							if (isContainsMissingTranslations) {
								LOGGER.warn("There is at least one district that couldn't be translated, refreshing districts translations from server...");
								refreshDistrictsTranslation();
								translatedData = filterPrevAndGetTranslatedData(redAlertEvent, prevData);
								//noinspection AssignmentUsedAsCondition
								if (isContainsMissingTranslations = translatedData.parallelStream().unordered().anyMatch(MissingAreaTranslationProtectionTime.class::isInstance))
									LOGGER.warn("There is at least one district that couldn't be translated after districts refreshment");
							}

							final Map<String, List<AreaTranslationProtectionTime>> unseenTranslatedDistricts = translatedData.parallelStream().unordered()
									.distinct() // TODO think about
									.filter(AreaTranslationProtectionTime.class::isInstance)
									.map(AreaTranslationProtectionTime.class::cast)
									.collect(Collectors.groupingBy(AreaTranslationProtectionTime::translatedAreaName)); // to know if new (unseen) districts were added from previous request

							final StringBuilder output = new StringBuilder();
							if (configuration.isDisplayResponse() && !unseenTranslatedDistricts.isEmpty())
								redAlertToString(
										contentLength,
										currAlertsLastModified,
										redAlertEvent,
										areaAndTranslatedDistrictsToString("Translated Areas and Districts", unseenTranslatedDistricts, redAlertEvent.cat()),
										output
								);
							if (configuration.isDisplayUntranslatedDistricts() && isContainsMissingTranslations) {
								output.append(translatedData.parallelStream().unordered()
										.distinct() // TODO think about
										.filter(MissingAreaTranslationProtectionTime.class::isInstance)
										.map(IAreaTranslationProtectionTime::translation)
										.sorted()
										.collect(Collectors.joining(
												"," + System.lineSeparator() + "\t",
												"Untranslated Districts:" + System.lineSeparator() + "\t",
												System.lineSeparator()
										)));
							}

							if (configuration.isMakeSound() || configuration.isAlertAll()) {
								final Map<String, List<AreaTranslationProtectionTime>> districtsForAlert = unseenTranslatedDistricts.values().parallelStream().unordered()
										.map(Collection::parallelStream)
										.flatMap(Stream::unordered)
										.filter(translationAndProtectionTime -> configuration.districtsOfInterest().contains(translationAndProtectionTime.translation()))
										.collect(Collectors.groupingBy(AreaTranslationProtectionTime::translatedAreaName)); // for not restarting alert sound unnecessarily
								if (!districtsForAlert.isEmpty()) {
									districtsForAlert.values().parallelStream().unordered()
											.map(Collection::parallelStream)
											.flatMap(Stream::unordered)
											.mapToInt(AreaTranslationProtectionTime::protectionTimeInSeconds)
											.max()
											.ifPresent(maxProtectionTime -> {
												clip.setFramePosition(0);
												//noinspection NumericCastThatLosesPrecision
												clip.loop(Math.max(1, (int) Math.round(maxProtectionTime / alarmSoundSecondLength)));
											});
									output.append(areaAndTranslatedDistrictsToString("ALERT ALERT ALERT", districtsForAlert, redAlertEvent.cat()));
								}
							}

							if (!output.isEmpty())
								System.out.println(output);

							printDistrictsNotFoundWarning();
							prevData.put(redAlertEvent.cat(), new HashSet<>(redAlertEvent.data()));
						}
					}
				} catch (JsonParseException e) {
					LOGGER.error("JSON parsing error: {}", e.toString());
				} catch (IOException e) {
					LOGGER.debug("Got exception: {}", e.toString());
					sleep();
				}
		} catch (Throwable e) {
			LOGGER.fatal("Closing connection and exiting...", e);
		}
	}

	private List<? extends IAreaTranslationProtectionTime> filterPrevAndGetTranslatedData(RedAlertEvent redAlertEvent,
																						  Map<Integer, Set<String>> prevData) {
		return redAlertEvent.data().parallelStream().unordered()
				.filter(Predicate.not(prevData.getOrDefault(redAlertEvent.cat(), Collections.emptySet())::contains))
				.map(key -> Option.of(districts.get(key)) instanceof Some(
						AreaTranslationProtectionTime areaTranslationProtectionTime
				) ?
						areaTranslationProtectionTime :
						new MissingAreaTranslationProtectionTime(key))
				.toList();
	}

	private StringBuilder redAlertToString(long contentLength,
										   TemporalAccessor alertsLastModified,
										   RedAlertEvent redAlertEvent,
										   String translatedData,
										   StringBuilder output) {
		return output.append("Translated Title: ").append(configuration.languageCode().getTitleTranslation(redAlertEvent.cat(), redAlertEvent.title())).append(System.lineSeparator())
				.append("Content Length: ").append(contentLength).append(" bytes").append(System.lineSeparator())
				.append("Last Modified Date: ").append(DATE_TIME_FORMATTER.format(alertsLastModified)).append(System.lineSeparator())
				.append("Current Date: ").append(DATE_TIME_FORMATTER.format(Instant.now())).append(System.lineSeparator())
				.append(translatedData);
	}

	private void refreshDistrictsTranslation() {
		final Map<String, AreaTranslationProtectionTime> updatedDistricts = loadRemoteDistricts(
				configuration.languageCode(),
				configuration.timeout(),
				district -> new AreaTranslationProtectionTime(
						district.areaname(),
						district.label(),
						district.migun_time()
				)
		);
		if (LOGGER.isDebugEnabled()) {
			final Map<String, AreaTranslationProtectionTime> newAndModifiedDistricts = updatedDistricts.entrySet().parallelStream().unordered()
					.filter(Predicate.not(districts.entrySet()::contains))
					.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
			if (!newAndModifiedDistricts.isEmpty())
				LOGGER.debug("New or modified districts: {}", newAndModifiedDistricts);
			final Map<String, AreaTranslationProtectionTime> deletedDistricts = districts.entrySet().parallelStream().unordered()
					.filter(Predicate.not(updatedDistricts.entrySet()::contains))
					.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
			if (!deletedDistricts.isEmpty())
				LOGGER.debug("Deleted districts: {}", deletedDistricts);
		}
		districts = updatedDistricts;
	}

	private static class LoggerLevelConverter implements CommandLine.ITypeConverter<Level> {
		@Override
		public Level convert(String value) {
			return Level.valueOf(value);
		}
	}
}
