package io.github.ashr123.red.alert;

import io.github.ashr123.logging.Json3ConfigurationFactory;
import io.github.ashr123.option.*;
import io.github.ashr123.timeMeasurement.Result;
import io.github.ashr123.timeMeasurement.TimeMeasurement;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import picocli.CommandLine;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@SuppressWarnings("InstanceVariableMayNotBeInitialized")
@CommandLine.Command(name = "java -jar red-alert-listener.jar",
		mixinStandardHelpOptions = true,
		versionProvider = Listener.class,
		showDefaultValues = true,
		description = "An App that can get \"red alert\" events from IDF's Home Front Command.")
public class Listener implements Runnable, CommandLine.IVersionProvider {
	static {
		System.setProperty("log4j.configurationFactory", Json3ConfigurationFactory.class.getName());
	}
	private static final ObjectMapper JSON_MAPPER = JsonMapper.builder()
			.enable(
					SerializationFeature.INDENT_OUTPUT,
					SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS
			)
//			.disable(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS)
			.build();

	private static final Logger LOGGER = LogManager.getLogger();
	private static final TypeReference<List<District>> DISTRICTS_TYPE_REFERENCE = new TypeReference<>() {
	};
	private static final TypeReference<List<AlertTranslations>> ALERTS_TRANSLATION_TYPE_REFERENCE = new TypeReference<>() {
	};
	private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(
					"yyyy-MM-dd HH:mm:ss,SSS",
					Locale.getDefault(Locale.Category.FORMAT)
			)
			.withZone(ZoneId.systemDefault());
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
	private static final ScopedValue<HttpClient> HTTP_CLIENT_SCOPED_VALUE = ScopedValue.newInstance();
//	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
//			.followRedirects(HttpClient.Redirect.NORMAL) //?
//			.build();

//	static {
//		Runtime.getRuntime().addShutdownHook(new Thread(HTTP_CLIENT::close));
//	}
	private static final Duration DISTRICTS_UPDATE_CONSTANT = ChronoUnit.HOURS.getDuration();
//	private static final Pattern
//			VAR_ALL_DISTRICTS = Pattern.compile("^.*=\\s*", Pattern.MULTILINE),
//			BOM = Pattern.compile("﻿");
//	private static final Collator COLLATOR = Collator.getInstance(Locale.ROOT);
	private static final URI ALERTS_TRANSLATION_URI = URI.create("https://www.oref.org.il/alerts/alertsTranslation.json");
	@CommandLine.Option(names = {"-c", "--configuration-file"},
			paramLabel = "configuration file",
			defaultValue = "red-alert-listener.conf.json",
			description = "Enter custom path to configuration file.")
	private File configurationFile;
	private volatile Configuration configuration = DEFAULT_CONFIGURATION;
	private volatile long configurationLastModified = 1;
	private volatile List<String> districtsNotFound = Collections.emptyList();
	private volatile boolean isContinue = true;
	/**
	 * Will be updated once a day from IDF's Home Front Command's server.
	 */
	private volatile Map<String, AreaTranslationProtectionTime> districts;
	private volatile HttpRequest httpRequest;
	private volatile LocalDateTime districtsLastUpdate;

	private Listener() {
	}

	private static void setLoggerLevel(Level level) {
		final LoggerContext loggerContext = (LoggerContext) LogManager.getContext(false);
		loggerContext.getConfiguration().getRootLogger().setLevel(level);
		loggerContext.updateLoggers();
	}

	private static void printHelpMsg() {
		System.err.println("""
				Available commands:
				\t• Enter "t" to perform sound test.
				\t• Enter "c" to clear the screen.
				\t• Enter "r" to refresh the districts translation dictionary.
				\t• Enter "l" to reload configuration file.
				\t• Enter "q" to quit.
				\t• Enter "h" to display this help message.""");
	}

	private static void sleep() {
		try {
			Thread.sleep(1000);
		} catch (InterruptedException interruptedException) {
			Thread.currentThread().interrupt(); // Restore interrupted state...
			//noinspection CallToPrintStackTrace
			interruptedException.printStackTrace(); //TODO think about
		}
	}

	private static Set<String> getTranslations(Collection<AreaTranslationProtectionTime> translatedData) {
		return translatedData.parallelStream().unordered()
				.map(AreaTranslationProtectionTime::translation)
				.collect(Collectors.toSet());
	}

	private static void printAlert(long contentLength,
								   TemporalAccessor alertsLastModified,
								   String translatedTitle,
								   String translatedDescription,
								   CharSequence output) {
		System.out.println("Translated Title: " + translatedTitle + System.lineSeparator() +
				"Translated Description: " + translatedDescription + System.lineSeparator() +
				"Content Length: " + contentLength + " bytes" + System.lineSeparator() +
				"Last Modified Date: " + DATE_TIME_FORMATTER.format(alertsLastModified) + System.lineSeparator() +
				"Current Date: " + DATE_TIME_FORMATTER.format(Instant.now()) + System.lineSeparator() +
				output);
	}

	private static int gzipSize(byte[] bytes) throws IOException {
		//noinspection UseOfConcreteClass
		try (CountingOutputStream countingOutputStream = new CountingOutputStream()) {
			try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(countingOutputStream) {{
				def = new Deflater(Deflater.BEST_COMPRESSION, true);
			}}) {
				// Write the string data to GZIPOutputStream
				gzipOutputStream.write(bytes);
			}
			// Return the compressed byte array size
			return countingOutputStream.getCount();
		}
	}

	private static <U> U merge(U value1, U value2) {
		LOGGER.trace("value1: {}, value2: {}", value1, value2);
		return value2;
	}

	static void main(String... args) {
		try (HttpClient httpClient = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL) //?
				.build()) {
			ScopedValue.where(HTTP_CLIENT_SCOPED_VALUE, httpClient)
					.run(() -> new CommandLine(Listener.class)
							.setCaseInsensitiveEnumValuesAllowed(true)
							.execute(args));
		}
	}

	private String areaAndTranslatedDistrictsToString(String headline,
													  List<AreaTranslationProtectionTime> districtsByAreaName,
													  int cat) {
//		final Function<AreaTranslationProtectionTime, String> toString = cat == 1 || cat == 101 ?
//				areaTranslationProtectionTime -> areaTranslationProtectionTime.translation() + " (" + configuration.languageCode().getTimeTranslation(areaTranslationProtectionTime.protectionTime()) + ")" :
//				AreaTranslationProtectionTime::translation;
		return (cat == 1 || cat == 101 ?
				districtsByAreaName.parallelStream().unordered()
						.collect(Collectors.groupingByConcurrent(
								AreaTranslationProtectionTime::translatedAreaName,
								Collectors.groupingByConcurrent(
										AreaTranslationProtectionTime::protectionTime,
										Collectors.mapping(
												AreaTranslationProtectionTime::translation,
												Collectors.toList()
										)
								)
						))
						.entrySet()
						.parallelStream().unordered()
						.sorted(Map.Entry.comparingByKey())
						.map(areaNameAndDuration -> areaNameAndDuration.getValue()
								.entrySet()
								.parallelStream().unordered()
								.sorted(Map.Entry.comparingByKey())
								.map(durationListEntry -> durationListEntry.getValue()
										.parallelStream().unordered()
										.sorted()
										.collect(Collectors.joining(
												"," + System.lineSeparator() + "\t\t\t",
												configuration.languageCode().getTimeTranslation(durationListEntry.getKey()) + ":" + System.lineSeparator() + "\t\t\t",
												""
										)))
								.collect(Collectors.joining(
										System.lineSeparator() + "\t\t",
										areaNameAndDuration.getKey() + ":" + System.lineSeparator() + "\t\t",
										""
								))) :
				districtsByAreaName.parallelStream().unordered()
						.collect(Collectors.groupingByConcurrent(
								AreaTranslationProtectionTime::translatedAreaName,
								Collectors.mapping(
										AreaTranslationProtectionTime::translation,
										Collectors.toList()
								)
						))
						.entrySet()
						.parallelStream().unordered()
						.sorted(Map.Entry.comparingByKey())
						.map(areaNameAndDistricts -> areaNameAndDistricts.getValue()
								.parallelStream().unordered()
								.sorted()
								.collect(Collectors.joining(
										"," + System.lineSeparator() + "\t\t",
										areaNameAndDistricts.getKey() + ":" + System.lineSeparator() + "\t\t",
										""
								))))
				.collect(Collectors.joining(
						System.lineSeparator() + "\t",
						headline + ":" + System.lineSeparator() + "\t",
						System.lineSeparator()
				));
	}

	private <T, K, V> Map<K, V> getResource(String headline,
											HttpRequest.Builder httpRequestBuilder,
											Function<InputStream, ? extends Collection<T>> jsonMapper,
											Function<Stream<T>, Map<K, V>> streamMapper,
											ToIntFunction<Map<?, V>> sizeFunction) {
		LOGGER.info("Getting {} from IDF's Home Front Command's server...", headline);
		final HttpRequest httpRequest = httpRequestBuilder.header("Accept", "application/json")
				.header("Accept-Encoding", "gzip")
				.header("Cache-Control", "no-store")
				.build();
		while (isContinue) {
			try {
				final Result<Map<K, V>> result = TimeMeasurement.measureAndExecuteCallable(() -> {
					final HttpResponse<InputStream> httpResponse = HTTP_CLIENT_SCOPED_VALUE.get().send(
							httpRequest,
							HttpResponse.BodyHandlers.ofInputStream()
					);
					try (InputStream inputStream = httpResponse.body()) {
						if (200 <= httpResponse.statusCode() && httpResponse.statusCode() < 300)
							try (InputStream body = "gzip".equalsIgnoreCase(httpResponse.headers().firstValue("Content-Encoding").orElse("")) ?
									new GZIPInputStream(inputStream) :
									inputStream) {
								return streamMapper.apply(jsonMapper.apply(body)
										.parallelStream().unordered());
							}
					}
					LOGGER.error("Got bad response status code: {}", httpResponse.statusCode());
					return Collections.emptyMap();
				});
				if (result.getResult().isEmpty()) {
					sleep();
					continue;
				}
				LOGGER.info(
						"Done (took {} milliseconds, got {} {})",
						result.getTimeTaken(),
						sizeFunction.applyAsInt(result.getResult()),
						headline
				);
				return result.getResult();
			} catch (JacksonException e) {
				LOGGER.error("JSON parsing error: {}", e.toString());
			} catch (Exception e) {
				LOGGER.error("Failed to get alerts translation for: {}. Trying again...", e.toString());
			}
			sleep();
		}
		return Collections.emptyMap();
	}

	/**
	 * @see <a href=https://www.oref.org.il/districts/districts_heb.json>districts_heb.json</a>
	 * @see <a href=https://www.oref.org.il/districts/districts_eng.json>districts_eng.json</a>
	 * @see <a href=https://www.oref.org.il/districts/districts_rus.json>districts_rus.json</a>
	 * @see <a href=https://www.oref.org.il/districts/districts_arb.json>districts_arb.json</a>
	 */
	private <T> Map<String, T> loadRemoteDistricts(LanguageCode languageCode,
												   Duration timeout,
												   Function<District, T> districtMapper) {
		return getResource(
				"districts",
				HttpRequest.newBuilder(URI.create("https://alerts-history.oref.org.il/Shared/Ajax/GetDistricts.aspx?lang=" + languageCode.name().toLowerCase(Locale.ROOT)))
						.timeout(timeout),
				body -> JSON_MAPPER.readValue(
						/*VAR_ALL_DISTRICTS.matcher(httpResponse.*/body/*()).replaceFirst("")*/,
						DISTRICTS_TYPE_REFERENCE
				),
				districtStream -> districtStream.collect(Collectors.toConcurrentMap(
						District::hebrewLabel,
						districtMapper,
						Listener::merge
				)),
				Map::size
		);
	}

	private Map<Integer, ? extends Map<String, AlertTranslations>> loadAlertsTranslation(Set<String> ignoredTitlesForAlert) {
		final Map<Integer, ? extends Map<String, AlertTranslations>> alertsTranslations = getResource(
				"alerts translations",
				HttpRequest.newBuilder(ALERTS_TRANSLATION_URI)
						.timeout(configuration.timeout()),
				body -> JSON_MAPPER.readValue(
						/*VAR_ALL_DISTRICTS.matcher(httpResponse.*/body/*()).replaceFirst("")*/,
						ALERTS_TRANSLATION_TYPE_REFERENCE
				),
				alertTranslationStream -> alertTranslationStream.filter(alertTranslations -> alertTranslations.matrixCatId() != 0)
						.collect(Collectors.groupingByConcurrent(
								AlertTranslations::matrixCatId,
								Collectors.toConcurrentMap(
										AlertTranslations::hebTitle,
										Function.identity(),
										Listener::merge
								)
						)),
				map -> map.values()
						.parallelStream().unordered()
						.mapToInt(Map::size)
						.sum()
		);

		final Set<String> nonExistentTitles = ignoredTitlesForAlert.parallelStream().unordered()
				.filter(Predicate.not(alertsTranslations.values()
						.parallelStream().unordered()
						.map(Map::keySet)
						.map(Collection::parallelStream)
						.flatMap(Stream::unordered)
						.collect(Collectors.toSet())
						::contains))
				.collect(Collectors.toSet());
		if (!nonExistentTitles.isEmpty())
			LOGGER.warn("Ignored titles for alert that don't exist: {}", nonExistentTitles);

		return alertsTranslations;
	}

	private <D> Map<String, D> startSubcommandInputThread(LanguageCode languageCode,
														  Duration timeout,
														  Level level,
														  Function<District, D> districtMapper) throws InterruptedException {
		final CountDownLatch startSignal = new CountDownLatch(1);
		Thread.startVirtualThread(() -> {
//			try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in))) {
			System.err.println("Enter \"q\" to quit");
			startSignal.countDown();
			while (isContinue)
//				switch (bufferedReader.readLine().strip()) {
				switch (IO.readln().strip()) {
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
//			} catch (IOException _) {
//			}
		});
		setLoggerLevel(level);
		startSignal.await();
		return loadRemoteDistricts(languageCode, timeout, districtMapper);
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
	) throws InterruptedException {
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
	) throws InterruptedException {
		JSON_MAPPER.writeValue(
				file,
				startSubcommandInputThread(
						languageCode,
						timeout,
						loggerLevel,
						Function.identity()
				)
						.entrySet()
						.parallelStream().unordered()
						.collect(Collectors.groupingByConcurrent(
								entry -> entry.getValue().areaName(),
								Collectors.groupingByConcurrent(
										entry -> entry.getValue().protectionTime().toSeconds(),
										Collectors.toConcurrentMap(
												Map.Entry::getKey,
												entry -> entry.getValue().label()
										)
								)
						))
		);
	}

	private void printDistrictsNotFoundWarning() {
		if (!districtsNotFound.isEmpty())
			LOGGER.warn("Those districts don't exist: {}", districtsNotFound);
	}

	@Override
	public String[] getVersion() {
		return new String[]{"Red Alert Listener v" + getClass().getPackage().getImplementationVersion()};
	}

	private synchronized void refreshDistrictsTranslation() {
		final Map<String, AreaTranslationProtectionTime> updatedDistricts = loadRemoteDistricts(
				configuration.languageCode(),
				configuration.timeout(),
				district -> new AreaTranslationProtectionTime(
						district.areaName(),
						district.label(),
						district.protectionTime()
				)
		);
		if (LOGGER.isDebugEnabled()) {
			final Map<String, AreaTranslationProtectionTime> newDistricts = updatedDistricts.entrySet()
					.parallelStream().unordered()
					.filter(o -> !districts.containsKey(o.getKey()))
					.collect(Collectors.toConcurrentMap(Map.Entry::getKey, Map.Entry::getValue));
			if (!newDistricts.isEmpty())
				LOGGER.debug("New districts: {}", newDistricts);

			final Map<String, AreaTranslationProtectionTime> modifiedDistricts = updatedDistricts.entrySet()
					.parallelStream().unordered()
					.filter(o -> {
						final AreaTranslationProtectionTime obj = districts.get(o.getKey());
						return obj != null && !o.getValue().equals(obj);
					})
					.collect(Collectors.toConcurrentMap(Map.Entry::getKey, Map.Entry::getValue));
			if (!modifiedDistricts.isEmpty())
				LOGGER.debug("Modified districts: {}", modifiedDistricts);

			final Map<String, AreaTranslationProtectionTime> deletedDistricts = districts.entrySet()
					.parallelStream().unordered()
					.filter(o -> !updatedDistricts.containsKey(o.getKey()))
					.collect(Collectors.toConcurrentMap(Map.Entry::getKey, Map.Entry::getValue));
			if (!deletedDistricts.isEmpty())
				LOGGER.debug("Deleted districts: {}", deletedDistricts);
		}
		districts = updatedDistricts;
		districtsLastUpdate = LocalDateTime.now();
	}

	@SuppressWarnings("UseOfConcreteClass")
	private synchronized void loadConfiguration(ClipManager clipManager) {
		final long configurationLastModifiedTemp = configurationFile.lastModified();
		final LanguageCode oldLanguageCode = configuration.languageCode();
		final Duration oldTimeout = configuration.timeout();
		if (configurationLastModifiedTemp > configurationLastModified) {
			LOGGER.info("(Re)Loading configuration from file \"{}\"", configurationFile);
			configuration = JSON_MAPPER.readValue(configurationFile, Configuration.class);
			configurationLastModified = configurationLastModifiedTemp;
			if (districts == null || !oldLanguageCode.equals(configuration.languageCode())) {
				refreshDistrictsTranslation();
				clipManager.prepareForOtherLanguage();
			}
			districtsNotFound = (configuration.districtsOfInterest().size() > 2 ?
					new ArrayList<>(configuration.districtsOfInterest()) :
					configuration.districtsOfInterest())
					.parallelStream().unordered()
					.filter(Predicate.not(getTranslations(new ArrayList<>(districts.values()))::contains))
					.toList();
			printDistrictsNotFoundWarning();
			setLoggerLevel(configuration.logLevel());
		} else if (configurationLastModifiedTemp == 0 && configurationLastModified != 0) {
			LOGGER.warn("couldn't find \"{}\", using default configuration", configurationFile);
			configuration = DEFAULT_CONFIGURATION;
			if (districts == null || !oldLanguageCode.equals(configuration.languageCode())) {
				refreshDistrictsTranslation();
				clipManager.prepareForOtherLanguage();
			}
			configurationLastModified = 0;
			districtsNotFound = Collections.emptyList();
			setLoggerLevel(configuration.logLevel());
		}
		if (httpRequest == null || !oldTimeout.equals(configuration.timeout()))
			httpRequest = HttpRequest.newBuilder(URI.create("https://www.oref.org.il/warningMessages/alert/Alerts.json"))
					.header("Accept", "application/json")
					.header("Accept-Encoding", "gzip")
					.header("Cache-Control", "no-store")
//					.header("X-Requested-With", "XMLHttpRequest")
//					.header("Referer", "https://www.oref.org.il/12481-" + configuration.languageCode().name().toLowerCase(Locale.ROOT) + "/Pakar.aspx")
					.timeout(configuration.timeout())
					.build();
	}

	private List<? extends IAreaTranslationProtectionTime> filterPrevAndGetTranslatedData(RedAlertEvent redAlertEvent,
																						  Map<Integer, Map<String, Set<String>>> prevDataMap) {
		final Set<String> prevData = prevDataMap.getOrDefault(redAlertEvent.cat(), Collections.emptyMap())
				.getOrDefault(redAlertEvent.title(), Collections.emptySet());
		Stream<String> stream = redAlertEvent.data()
				.parallelStream().unordered();
		if (!prevData.isEmpty())
			stream = stream.filter(Predicate.not(prevData::contains));
		return stream.map(key -> {
					final AreaTranslationProtectionTime translationProtectionTime = districts.get(key);
					return translationProtectionTime == null ?
							new MissingTranslation(key) :
							translationProtectionTime;
				})
				.toList();
	}

	@Override
	public void run() {
		System.err.println("Preparing " + getVersion()[0] + "...");
		printHelpMsg();
		final HttpClient httpClient = HTTP_CLIENT_SCOPED_VALUE.get();
		//noinspection UseOfConcreteClass
		try (ClipManager clipManager = new ClipManager();
			 ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory())) {
			final CountDownLatch startSignal = new CountDownLatch(1);
			Thread.startVirtualThread(() -> {
//				try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in))) {
				startSignal.countDown();
				while (isContinue)
//					switch (bufferedReader.readLine().strip()) {
					switch (IO.readln().strip()) {
						case "" -> {
						}
						case "q", "quit", "exit" -> isContinue = false;
						case "t", "test", "test-sound" -> {
							System.err.println("Testing sound...");
							clipManager.playDefaultClip();
						}
						case "c", "clear" -> System.err.println("\033[H\033[2JListening...");
						case "r", "refresh", "refresh-districts" -> Thread.startVirtualThread(() -> ScopedValue.where(HTTP_CLIENT_SCOPED_VALUE, httpClient)
								.run(this::refreshDistrictsTranslation));
						case "h", "help" -> printHelpMsg();
						case "l", "load-configuration" -> Thread.startVirtualThread(() -> ScopedValue.where(HTTP_CLIENT_SCOPED_VALUE, httpClient)
								.run(() -> {
									try {
										loadConfiguration(clipManager);
									} catch (JacksonException e) {
										LOGGER.error("Configuration error: {}", e.toString());
									}
								}));
						default -> {
							System.err.println("Unrecognized command!");
							printHelpMsg();
						}
					}
//				} catch (IOException _) {
//				}
				System.err.println("Bye Bye!");
			});
			scheduledExecutorService.scheduleAtFixedRate(
					() -> ScopedValue.where(HTTP_CLIENT_SCOPED_VALUE, httpClient)
							.run(this::refreshDistrictsTranslation),
					1,
					1,
					TimeUnit.DAYS
			);
			loadConfiguration(clipManager);

			final Set<String> ignoredTitlesForAlert = Set.of(
					"ניתן לצאת מהמרחב המוגן",
					"ניתן לצאת מהמרחב המוגן אך יש להישאר בקרבתו", // ??
					"סיום שהייה בסמיכות למרחב המוגן",
					"חדירת מחבלים -  החשש הוסר",
					"הסתיים אירוע חדירת מחבלים - ניתן לצאת מהבתים",
					"חומרים מסוכנים - האירוע הסתיים",
					"אירוע חומרים מסוכנים - הסכנה באזורכם חלפה",
					"חדירת כלי טיס עוין - האירוע הסתיים",
					"סכנת פיצוץ והדף חזק - ניתן לצאת מהמרחב המוגן",
					"ירי רקטות וטילים -  האירוע הסתיים",
					"אירוע בקריה למחקר גרעיני בנגב  – ניתן לצאת ממבנים",
					"אירוע במרכז למחקר גרעיני בשורק  – ניתן לצאת ממבנים",
					"התרעה על רעידת אדמה - ניתן לחזור לשגרה",
					"בעקבות רעידת האדמה - הנחיות לחזרה למבנים", // ??
					"הנחיות בעקבות רעידת האדמה" // ??
			);

			final var ref = new Object() {
				private Instant currAlertsLastModified = Instant.MIN;
				private Map<Integer, ? extends Map<String /*hebTitle*/, AlertTranslations>> alertsTranslations = loadAlertsTranslation(ignoredTitlesForAlert);
			};

			final Map<Integer, Map<String /*title*/, Set<String>>> prevData = new ConcurrentHashMap<>(ref.alertsTranslations.size());

// 			language=JSON
//			final long minRedAlertEventContentLength2 = """
//					{"cat":"1","data":[],"desc":"","id":0,"title":""}""".getBytes().length;
			//language=JSON
			final long minRedAlertEventContentLength = gzipSize("""
					{"cat":"1","data":[],"desc":"","id":0,"title":""}""".getBytes());

			startSignal.await();
			System.err.println("Listening...");
			while (isContinue)
				try {
//					loadConfiguration();
					final HttpResponse<InputStream> httpResponse = httpClient.send(
							httpRequest,
							HttpResponse.BodyHandlers.ofInputStream()
					);

					try (InputStream inputStream = httpResponse.body()) {
						if (httpResponse.statusCode() < 200 || 300 <= httpResponse.statusCode()) {
							LOGGER.error("Connection response status code: {}", httpResponse.statusCode());
							sleep();
							continue;
						}
						switch (OptionLong.of(httpResponse.headers().firstValueAsLong("Content-Length"))) {
							case SomeLong(long contentLength) when contentLength > minRedAlertEventContentLength -> {
								//noinspection NestedSwitchStatement
								switch (Option.of(httpResponse.headers().firstValue("Last-Modified"))
										.map(lastModified -> DateTimeFormatter.RFC_1123_DATE_TIME.parse(lastModified, Instant::from))) {
									case Some(Instant lastModified) when ref.currAlertsLastModified.isBefore(lastModified) -> {
										try (InputStream body = "gzip".equalsIgnoreCase(httpResponse.headers().firstValue("Content-Encoding").orElse("")) ?
												new GZIPInputStream(inputStream) :
												inputStream) {
											final RedAlertEvent redAlertEvent = JSON_MAPPER.readValue(
													/*BOM.matcher(httpResponse.*/body/*()).replaceFirst("")*/,
													RedAlertEvent.class
											);

											ref.currAlertsLastModified = lastModified;

											LOGGER.debug(
													"Original event data: {}, processing took {} milliseconds",
													redAlertEvent,
													TimeMeasurement.measureAndExecute(() -> {
																AlertTranslations alertTranslations = Option.of(ref.alertsTranslations.get(redAlertEvent.cat())) instanceof Some(Map<String, AlertTranslations> map) ?
																		map.get(redAlertEvent.title()) :
																		null;
																if (alertTranslations == null) {
																	LOGGER.warn("Couldn't find translation for cat: {} ({}), trying again...", redAlertEvent.cat(), redAlertEvent.title());
																	alertTranslations = Option.of((ref.alertsTranslations = loadAlertsTranslation(ignoredTitlesForAlert))
																			.get(redAlertEvent.cat())) instanceof Some(Map<String, AlertTranslations> map) ?
																			map.get(redAlertEvent.title()) :
																			null;
																}
																final String
																		title = alertTranslations == null ?
																				redAlertEvent.title() + " (didn't find translation)" :
																				alertTranslations.getAlertTitle(configuration.languageCode()),
																		description = alertTranslations == null ?
																				redAlertEvent.desc() + " (didn't find translation)" :
																				alertTranslations.getAlertText(configuration.languageCode());

																//TODO rethink of what defines a drill alert
																if (redAlertEvent.data()
																		.parallelStream().unordered()
																		.allMatch(LanguageCode.HE::containsTestKey)) {
																	if (configuration.isShowTestAlerts())
																		printAlert(
																				contentLength,
																				lastModified,
																				title,
																				description,
																				redAlertEvent.data()
																						.parallelStream().unordered()
																						.map(configuration.languageCode()::getTestTranslation)
																						.sorted()
																						.collect(Collectors.joining(
																								"," + System.lineSeparator() + "\t",
																								"Test Alert:" + System.lineSeparator() + "\t",
																								System.lineSeparator()
																						))
																		);
																	return;
																}

																List<? extends IAreaTranslationProtectionTime> translatedData = filterPrevAndGetTranslatedData(redAlertEvent, prevData);

																boolean isContainsMissingTranslations = translatedData.parallelStream().unordered()
																		.anyMatch(MissingTranslation.class::isInstance);
																if (isContainsMissingTranslations) {
																	if (Duration.between(districtsLastUpdate, LocalDateTime.now()).compareTo(DISTRICTS_UPDATE_CONSTANT) > 0) {
																		LOGGER.warn("There is at least one district that couldn't be translated, refreshing districts translations from server...");
																		refreshDistrictsTranslation();
																		//noinspection AssignmentUsedAsCondition
																		if (isContainsMissingTranslations = (translatedData = filterPrevAndGetTranslatedData(redAlertEvent, prevData))
																				.parallelStream().unordered()
																				.anyMatch(MissingTranslation.class::isInstance))
																			LOGGER.warn("There is at least one district that couldn't be translated after districts refreshment");
																	} else
																		LOGGER.warn("There is at least one district that couldn't be translated");
																}

																final List<AreaTranslationProtectionTime>
																		unseenTranslatedDistricts = translatedData.parallelStream().unordered()
																				.distinct() //TODO think about
																				.filter(AreaTranslationProtectionTime.class::isInstance)
																				.map(AreaTranslationProtectionTime.class::cast)
																				.toList(), //to know if new (unseen) districts were added since the previous request.
																		districtsForAlert = unseenTranslatedDistricts.parallelStream().unordered()
																				.filter(translationAndProtectionTime -> configuration.districtsOfInterest().contains(translationAndProtectionTime.translation()))
																				.toList(); //for not restarting alert sound unnecessarily

																final StringBuilder output = new StringBuilder();

																if (!unseenTranslatedDistricts.isEmpty())
																	output.append(areaAndTranslatedDistrictsToString("Translated Areas and Districts", unseenTranslatedDistricts, redAlertEvent.cat()));

																if (isContainsMissingTranslations && configuration.isDisplayUntranslatedDistricts())
																	output.append(translatedData.parallelStream().unordered()
																			.distinct() //TODO think about
																			.filter(MissingTranslation.class::isInstance)
																			.map(MissingTranslation.class::cast)
																			.map(MissingTranslation::untranslatedName)
																			.sorted()
																			.collect(Collectors.joining(
																					"," + System.lineSeparator() + "\t",
																					"Untranslated Districts:" + System.lineSeparator() + "\t",
																					System.lineSeparator()
																			)));

																if (Option.of((configuration.isAlertAll() ? unseenTranslatedDistricts : districtsForAlert)
																		.parallelStream().unordered()
																		.map(AreaTranslationProtectionTime::protectionTime)
																		.min(Comparator.naturalOrder())) instanceof Some(Duration minProtectionTime)) {
																	if (configuration.isMakeSound() && !ignoredTitlesForAlert.contains(redAlertEvent.title())) {
																		clipManager.playClip(redAlertEvent.cat(), configuration.languageCode(), minProtectionTime);
																	}
																	output.append(areaAndTranslatedDistrictsToString("ALERT ALERT ALERT", districtsForAlert, redAlertEvent.cat()));
																}

																if (!output.isEmpty())
																	printAlert(
																			contentLength,
																			lastModified,
																			title,
																			description,
																			output
																	);

																printDistrictsNotFoundWarning();
																prevData.computeIfAbsent(redAlertEvent.cat(), _ -> new ConcurrentHashMap<>())
																		.put(redAlertEvent.title(), new HashSet<>(redAlertEvent.data()));
															})
															.getTimeTaken()
											);
										}
									}
									case Some<Instant> _ -> {
									}
									case None<Instant> _ -> LOGGER.error("Couldn't get last modified date");
								}
							}
							case SomeLong _ when !prevData.isEmpty() -> prevData.clear();
							case SomeLong _ -> {
							}
							case NoneLong _ -> LOGGER.error("Couldn't get content length");
						}
					}
				} catch (JacksonException e) {
					LOGGER.error("JSON parsing error: {}", e.toString());
				} catch (IOException e) {
					if (Option.of(e.getMessage()) instanceof Some(String message) &&
							message.endsWith("GOAWAY received"))
						LOGGER.trace("Got GOAWAY: {}", e.toString());
					else {
						LOGGER.debug("Got exception: {}", e.toString());
						sleep();
					}
				}
		} catch (Throwable e) {
			LOGGER.fatal("Closing connection and exiting...", e);
		}
	}

	private static class LoggerLevelConverter implements CommandLine.ITypeConverter<Level> {
		@Override
		public Level convert(String value) {
			return Level.valueOf(value);
		}
	}

	private static class CountingOutputStream extends OutputStream {
		private int count;

		@Override
		public void write(int b) {
			++count;
		}

		@Override
		public void write(byte[] b, int off, int len) {
			count += len;
		}

		public int getCount() {
			return count;
		}

		@Override
		public String toString() {
			return "CountingOutputStream{" +
					"count=" + count +
					'}';
		}
	}
}
