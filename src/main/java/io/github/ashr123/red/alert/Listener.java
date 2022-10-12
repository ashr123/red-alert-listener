package io.github.ashr123.red.alert;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.ashr123.timeMeasurement.Result;
import io.github.ashr123.timeMeasurement.TimeMeasurement;
import io.github.ashr123.timeMeasurement.TimeScales;
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
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@CommandLine.Command(name = "red-alert-listener",
		mixinStandardHelpOptions = true,
		versionProvider = Listener.class,
		showDefaultValues = true,
		description = "An App that can get \"red alert\"s from IDF's Home Front Command.")
public class Listener implements Runnable, CommandLine.IVersionProvider
{
	private static final ZoneId DEFAULT_ZONE_ID = ZoneId.systemDefault();
	private static final TypeReference<List<District>> LIST_TYPE_REFERENCE = new TypeReference<>()
	{
	};
	private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(FixedDateFormat.FixedFormat.DEFAULT.getPattern());
	private static final Logger LOGGER = LogManager.getLogger();
	private static final ObjectMapper JSON_MAPPER = new JsonMapper()
			.enable(SerializationFeature.INDENT_OUTPUT)
			.disable(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS)
			.findAndRegisterModules();
	private static final Configuration DEFAULT_CONFIGURATION = new Configuration(
			false,
			false,
			true,
			false,
			Duration.ofMillis(10000),
			LanguageCode.HE,
			Level.INFO,
			Collections.emptySet()
	);
	private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
	private static final Pattern
			VAR_ALL_DISTRICTS = Pattern.compile(".*=\\s*", Pattern.MULTILINE),
			BOM = Pattern.compile("﻿");
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
	private volatile Map<String, TranslationAndProtectionTime> districts;

	public static void main(String... args)
	{
		System.exit(new CommandLine(Listener.class)
				.setCaseInsensitiveEnumValuesAllowed(true)
				.execute(args));
	}

	private static void setLoggerLevel(Level level)
	{
		final LoggerContext loggerContext = (LoggerContext) LogManager.getContext(false);
		loggerContext.getConfiguration().getLoggerConfig(LOGGER.getName()).setLevel(level);
		loggerContext.updateLoggers();
	}

	private static void printHelpMsg()
	{
		System.err.println("Enter \"t\" for sound test, \"c\" for clearing the screen, \"r\" for refresh the districts translation dictionary, \"q\" to quit or \"h\" for displaying this help massage.");
	}

	private static void sleep()
	{
		try
		{
			Thread.sleep(1000);
		} catch (InterruptedException interruptedException)
		{
			interruptedException.printStackTrace(); // TODO think about
		}
	}

	private static Set<String> getTranslationFromTranslationAndProtectionTime(Collection<TranslationAndProtectionTime> translatedData)
	{
		return translatedData.parallelStream().unordered()
				.filter(Objects::nonNull)
				.map(TranslationAndProtectionTime::translation)
				.collect(Collectors.toSet());
	}

	@CommandLine.Command(mixinStandardHelpOptions = true,
			versionProvider = Listener.class,
			showDefaultValues = true,
			description = "Gets all supported districts translation from Hebrew from IDF's Home Front Command's server and print it to stdout (No need for configuration file).")
	private void getRemoteDistrictsAsJSON(
			@CommandLine.Option(names = {"-l", "--language"},
					paramLabel = "language code",
					required = true,
					description = "Which language's translation to get? Valid values: ${COMPLETION-CANDIDATES} (case insensitive).")
			LanguageCode languageCode,
			@CommandLine.Option(names = {"-t", "--timeout"},
					paramLabel = "timeout",
					defaultValue = "10000",
					description = "Timeout for connecting to IDF's Home Front Command's server.")
			long timeout,
			@CommandLine.Option(names = {"-L", "--logger-level"},
					paramLabel = "logger level",
					defaultValue = "INFO",
					converter = LoggerLevelConverter.class,
					description = "Level of logger. Valid values: OFF, FATAL, ERROR, WARN, INFO, DEBUG, TRACE, ALL (case insensitive).")
			Level loggerLevel
	) throws IOException, InterruptedException
	{
		try (InputStream ignored = System.in)
		{
			System.out.println(JSON_MAPPER.writeValueAsString(startSubcommandInputThread(
					languageCode,
					timeout,
					loggerLevel
			)));
		}
	}

	@CommandLine.Command(mixinStandardHelpOptions = true,
			versionProvider = Listener.class,
			showDefaultValues = true,
			description = "Gets all supported districts translation from Hebrew from IDF's Home Front Command's server and print it to file (No need for configuration file).")
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
					defaultValue = "10000",
					description = "Timeout for connecting to IDF's Home Front Command's server.")
			long timeout,
			@CommandLine.Option(names = {"-L", "--logger-level"},
					paramLabel = "logger level",
					defaultValue = "INFO",
					converter = LoggerLevelConverter.class,
					description = "Level of logger. Valid values: OFF, FATAL, ERROR, WARN, INFO, DEBUG, TRACE, ALL (case insensitive).")
			Level loggerLevel
	) throws IOException, InterruptedException
	{
		try (InputStream ignored = System.in)
		{
			JSON_MAPPER.writeValue(
					file,
					startSubcommandInputThread(
							languageCode,
							timeout,
							loggerLevel
					)
			);
		}
	}

	private Map<String, String> startSubcommandInputThread(
			LanguageCode languageCode,
			long timeout,
			Level level
	) throws InterruptedException
	{
		final CountDownLatch startSignal = new CountDownLatch(1);
		new Thread(() ->
		{
			try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8))
			{
				System.err.println("Enter \"q\" to quit");
				startSignal.countDown();
				while (isContinue)
					switch (scanner.nextLine().strip())
					{
						case "" ->
						{
						}
						case "q" ->
						{
							System.err.println("Quiting...");
							isContinue = false;
						}
						default -> System.err.println("""
								Unrecognized command!
								Enter "q" to quit""");
					}
			} catch (NoSuchElementException ignored)
			{
			}
		}).start();
		setLoggerLevel(level);
		startSignal.await();
		return loadRemoteDistricts(languageCode, Duration.ofMillis(timeout), District::label);
	}

	private <T> Map<String, T> loadRemoteDistricts(
			LanguageCode languageCode,
			Duration timeout,
			Function<District, T> districtMapper
	)
	{
		LOGGER.info("Getting remote districts from IDF's Home Front Command's server...");
		while (isContinue)
		{
			try
			{
				final Result<Map<String, T>> result = TimeMeasurement.measureAndExecuteCallable(() ->
				{
					final HttpResponse<String> httpResponse = HTTP_CLIENT.send(
							HttpRequest.newBuilder(URI.create("https://www.oref.org.il/Shared/Ajax/GetDistricts.aspx?lang=" + languageCode.name().toLowerCase()))
									.header("Accept", "application/json")
									.timeout(timeout)
									.build(),
							HttpResponse.BodyHandlers.ofString()
					);
					if (httpResponse.statusCode() == HttpURLConnection.HTTP_OK)
					{
						return JSON_MAPPER.readValue(
										VAR_ALL_DISTRICTS.matcher(httpResponse.body()).replaceFirst(""),
										LIST_TYPE_REFERENCE
								)
								.parallelStream().unordered()
								.collect(Collectors.toMap(
										District::label_he,
										districtMapper,
										(value1, value2) ->
										{
											LOGGER.trace("value1: {}, value2: {}", value1, value2);
											return value2;
										}
								));
					}
					LOGGER.error("Got bad response status code: {}", httpResponse.statusCode());
					return null;
				});
				if (result.getResult() == null)
				{
					sleep();
					continue;
				}
				LOGGER.info("Done (took {} seconds, got {} districts)", result.getTimeTaken(TimeScales.SECONDS), result.getResult().size());
				return result.getResult();
			} catch (JsonParseException e)
			{
				LOGGER.error("JSON parsing error: {}", e.toString());
			} catch (Exception e)
			{
				LOGGER.debug("Failed to get data for language code {}: {}. Trying again...", languageCode, e.toString());
			}
			sleep();
		}
		return Collections.emptyMap();
	}

	private void printDistrictsNotFoundWarning()
	{
		if (!districtsNotFound.isEmpty())
			LOGGER.warn("Those districts don't exist: {}", districtsNotFound);
	}

	@Override
	public String[] getVersion()
	{
		return new String[]{"Red Alert Listener v" + getClass().getPackage().getImplementationVersion()};
	}

	private void loadConfiguration() throws IOException
	{
		final long configurationLastModifiedTemp = configurationFile.lastModified();
		final LanguageCode oldLanguageCode = configuration.languageCode();
		if (configurationLastModifiedTemp > configurationLastModified)
		{
			LOGGER.info("(Re)Loading configuration from file \"{}\"", configurationFile);
			configuration = JSON_MAPPER.readValue(configurationFile, Configuration.class);
			configurationLastModified = configurationLastModifiedTemp;
			if (districts == null || !oldLanguageCode.equals(configuration.languageCode()))
				refreshDistrictsTranslation();
			districtsNotFound = (configuration.districtsOfInterest().size() > 2 ?
					new ArrayList<>(configuration.districtsOfInterest()) :
					configuration.districtsOfInterest()).parallelStream().unordered()
					.filter(Predicate.not(getTranslationFromTranslationAndProtectionTime(new ArrayList<>(districts.values()))::contains))
					.toList();
			printDistrictsNotFoundWarning();
			setLoggerLevel(configuration.logLevel());
		} else if (configurationLastModifiedTemp == 0 && configurationLastModified != 0)
		{
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
	public void run()
	{
		System.err.println("Preparing Red Alert Listener v" + getClass().getPackage().getImplementationVersion() + "...");
		try (Clip clip = AudioSystem.getClip(Stream.of(AudioSystem.getMixerInfo()).parallel().unordered()
				.filter(mixerInfo -> "default [default]".equals(mixerInfo.getName()))
				.findAny()
				.orElse(null));
			 AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(new BufferedInputStream(Objects.requireNonNull(getClass().getResourceAsStream("/alarmSound.wav"))));
			 ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
			 InputStream in = System.in)
		{
			clip.open(audioInputStream);
			new Thread(() ->
			{
				try (Scanner scanner = new Scanner(in, StandardCharsets.UTF_8))
				{
					printHelpMsg();
					while (isContinue)
						switch (scanner.nextLine().strip())
						{
							case "" ->
							{
							}
							case "q", "quit", "exit" -> isContinue = false;
							case "t", "test", "test-sound" ->
							{
								System.err.println("Testing sound...");
								clip.setFramePosition(0);
								clip.start();
							}
							case "c", "clear" -> System.err.println("\033[H\033[2JListening...");
							case "r", "refresh", "refresh-districts" -> refreshDistrictsTranslation();
							case "h", "help" -> printHelpMsg();
							default ->
							{
								System.err.println("Unrecognized command!");
								printHelpMsg();
							}
						}
				} catch (NoSuchElementException ignored)
				{
				}
				System.err.println("Bye Bye!");
			}).start();
			scheduledExecutorService.scheduleAtFixedRate(this::refreshDistrictsTranslation, 1, 1, TimeUnit.DAYS);
			loadConfiguration();
			final URI uri = URI.create("https://www.oref.org.il/WarningMessages/alert/alerts.json");
			Set<String> prevData = Collections.emptySet();
			Instant currAlertsLastModified = Instant.MIN;
			final int minRedAlertEventContentLength = """
					{"cat":"1","data":[],"desc":"","id":0,"title":""}""".getBytes(StandardCharsets.UTF_8).length;
			final double alarmSoundSecondLength = clip.getMicrosecondLength() / 1E6;
			System.err.println("Listening...");
			while (isContinue)
				try
				{
					loadConfiguration();
					final HttpResponse<String> httpResponse = HTTP_CLIENT.send(
							HttpRequest.newBuilder(uri)
									.header("Accept", "application/json")
									.header("X-Requested-With", "XMLHttpRequest")
									.header("Referer", "https://www.oref.org.il/12481-" + configuration.languageCode().name().toLowerCase() + "/Pakar.aspx")
									.timeout(configuration.timeout())
									.build(),
							HttpResponse.BodyHandlers.ofString()
					);

					if (httpResponse.statusCode() != HttpURLConnection.HTTP_OK/* &&
								httpURLConnection.getResponseCode() != HttpURLConnection.HTTP_NOT_MODIFIED*/)
					{
						LOGGER.error("Connection response status code: {}", httpResponse.statusCode());
						sleep();
						continue;
					}
					final Instant alertsLastModified;
					final long contentLength = httpResponse.headers().firstValueAsLong("Content-Length").orElse(-1);
					if (contentLength < minRedAlertEventContentLength)
						prevData = Collections.emptySet();
					else if ((alertsLastModified = httpResponse.headers().firstValue("Last-Modified")
							.map(lastModifiedStr -> DateTimeFormatter.RFC_1123_DATE_TIME.parse(lastModifiedStr, Instant::from))
							.filter(currAlertsLastModified::isBefore)
							.orElse(null)) != null)
					{
						currAlertsLastModified = alertsLastModified;

						final RedAlertEvent redAlertEvent = JSON_MAPPER.readValue(
								BOM.matcher(httpResponse.body()).replaceFirst(""),
								RedAlertEvent.class
						);
						LOGGER.debug("Original event data: {}", redAlertEvent);
						// TODO rethink of what defines a drill alert
						if (redAlertEvent.data().parallelStream().unordered()
								.allMatch(LanguageCode.HE::containsTestKey))
						{
							if (configuration.isShowTestAlerts())
								System.out.println(redAlertToString(
										contentLength,
										alertsLastModified,
										redAlertEvent,
										redAlertEvent.data().parallelStream().unordered()
												.map(configuration.languageCode()::getTranslation)
												.toList(),
										new StringBuilder("Test Alert").append(System.lineSeparator())
								));
							continue;
						}

						List<TranslationAndProtectionTime> translatedData = getTranslatedData(redAlertEvent);

						if (translatedData.contains(null))
						{
							LOGGER.warn("There is at least one district that couldn't be translated, refreshing districts translations from server...");
							refreshDistrictsTranslation();
							translatedData = getTranslatedData(redAlertEvent);
							if (translatedData.contains(null))
								LOGGER.warn("There is at least one district that couldn't be translated after districts refreshment");
						}

						Set<String> finalPrevData = prevData;
						final List<TranslationAndProtectionTime>
								unseenTranslatedDistricts = translatedData.parallelStream().unordered()
								.filter(Objects::nonNull)
								.filter(translationAndProtectionTime -> !finalPrevData.contains(translationAndProtectionTime.translation()))
								.toList(), // to know if new (unseen) districts were added from previous request.
								newDistrictsOfInterest = unseenTranslatedDistricts.parallelStream().unordered()
										.filter(translationAndProtectionTime -> configuration.districtsOfInterest().contains(translationAndProtectionTime.translation()))
										.toList(); // for not restarting alert sound unnecessarily

						if (configuration.isMakeSound() && (configuration.isAlertAll() || !newDistrictsOfInterest.isEmpty()))
							newDistrictsOfInterest.parallelStream().unordered()
									.mapToInt(TranslationAndProtectionTime::protectionTime)
									.max()
									.ifPresent(maxProtectionTime ->
									{
										clip.setFramePosition(0);
										clip.loop(Math.max(1, (int) Math.round(maxProtectionTime / alarmSoundSecondLength)));
									});
						final Set<String> unseenTranslatedStrings = getTranslationFromTranslationAndProtectionTime(unseenTranslatedDistricts);
						final StringBuilder output = new StringBuilder();
						if (configuration.isDisplayResponse() && !unseenTranslatedStrings.isEmpty())
							redAlertToString(
									contentLength,
									alertsLastModified,
									redAlertEvent,
									unseenTranslatedStrings,
									output
							);

						if (!newDistrictsOfInterest.isEmpty())
							output.append("ALERT ALERT ALERT: ").append(newDistrictsOfInterest).append(System.lineSeparator());
						if (!output.isEmpty())
							System.out.println(output);

						printDistrictsNotFoundWarning();
						prevData = getTranslationFromTranslationAndProtectionTime(translatedData);
					}
				} catch (JsonParseException e)
				{
					LOGGER.error("JSON parsing error: {}", e.toString());
				} catch (IOException e)
				{
					LOGGER.debug("Got exception: {}", e.toString());
					sleep();
				}
		} catch (Throwable e)
		{
			LOGGER.fatal("Closing connection and exiting...", e);
		}
	}

	private List<TranslationAndProtectionTime> getTranslatedData(RedAlertEvent redAlertEvent)
	{
		return redAlertEvent.data().parallelStream().unordered()
				.map(districts::get)
				.toList();
	}

	private StringBuilder redAlertToString(long contentLength,
										   Instant alertsLastModified,
										   RedAlertEvent redAlertEvent,
										   Collection<String> translatedData,
										   StringBuilder output)
	{
		return output.append("Translated title: ").append(configuration.languageCode().getTitleTranslation(redAlertEvent.cat(), redAlertEvent.title())).append(System.lineSeparator())
				.append("Content Length: ").append(contentLength).append(" bytes").append(System.lineSeparator())
				.append("Last Modified Date: ").append(DATE_TIME_FORMATTER.format(alertsLastModified.atZone(DEFAULT_ZONE_ID))).append(System.lineSeparator())
				.append("Current Date: ").append(DATE_TIME_FORMATTER.format(ZonedDateTime.now())).append(System.lineSeparator())
				.append("Translated districts: ").append(translatedData).append(System.lineSeparator());
	}

	private void refreshDistrictsTranslation()
	{
		districts = loadRemoteDistricts(
				configuration.languageCode(),
				configuration.timeout(),
				district -> new TranslationAndProtectionTime(district.label(), district.migun_time())
		);
	}

	@SuppressWarnings("unused")
	private enum LanguageCode
	{
		HE(
				Map.ofEntries(
						Map.entry("בדיקה", "בדיקה"),
						Map.entry("בדיקה מחזורית", "בדיקה מחזורית")
				),
				null
		),
		EN(
				Map.ofEntries(
						Map.entry("בדיקה", "Test"),
						Map.entry("בדיקה מחזורית", "Periodic Test")
				),
				Map.ofEntries(
						Map.entry(1, "Rocket and missile fire"),
						Map.entry(3, "Earthquake"),
						Map.entry(4, "Radiological event"),
						Map.entry(5, "Fear of a tsunami"),
						Map.entry(6, "Hostile aircraft intrusion"),
						Map.entry(7, "Hazardous Materials Event"),
						Map.entry(13, "Terrorist infiltration"),
						Map.entry(101, "Rocket and missile fire drill"),
						Map.entry(103, "Earthquake drill"),
						Map.entry(105, "Tsunami drill"),
						Map.entry(107, "Hazardous Materials drill"),
						Map.entry(113, "Terrorist infiltration drill")
				)
		),
		AR(
				Map.ofEntries(
						Map.entry("בדיקה", "فحص"),
						Map.entry("בדיקה מחזורית", "فحص الدوري")
				),
				Map.ofEntries(
						Map.entry(1, "اطلاق قذائف وصواريخ"),
						Map.entry(3, "هزّة أرضية"),
						Map.entry(4, "حدث إشعاعي"),
						Map.entry(5, "تحسبا للتسونامي"),
						Map.entry(6, "اختراق طائرة معادية"),
						Map.entry(7, "حدث مواد خطرة"),
						Map.entry(13, "تسلل مخربين"),
						Map.entry(101, "تمرين اطلاق قذائف وصواريخ"),
						Map.entry(103, "تمرين هزّة أرضية"),
						Map.entry(105, "تمرين تسونامي"),
						Map.entry(107, "تمرين مواد خطرة"),
						Map.entry(113, "تمرين تسلل مخربين")
				)
		),
		RU(
				Map.ofEntries(
						Map.entry("בדיקה", "Проверка"),
						Map.entry("בדיקה מחזורית", "Периодическая Проверка")
				),
				Map.ofEntries(
						Map.entry(1, "Ракетный обстрел"),
						Map.entry(3, "Землетрясение"),
						Map.entry(4, "Радиоактивная опасность"),
						Map.entry(5, "Угроза цунами"),
						Map.entry(6, "Нарушение воздушного пространства"),
						Map.entry(7, "Утечка опасных веществ"),
						Map.entry(13, "Проникновение террористов"),
						Map.entry(101, "Учения по ракетному обстрелу"),
						Map.entry(103, "Учения на случай землетрясения"),
						Map.entry(105, "Учения на случай цунами"),
						Map.entry(107, "Учения на случай утечки опасных веществ"),
						Map.entry(113, "Учения на случай проникновения террористов")
				)
		);
		private final Map<String, String> testDistrictTranslations;
		private final Map<Integer, String> titleTranslations;

		LanguageCode(Map<String, String> testDistrictTranslations, Map<Integer, String> titleTranslations)
		{
			this.testDistrictTranslations = testDistrictTranslations;
			this.titleTranslations = titleTranslations;
		}

		public boolean containsTestKey(String key)
		{
			return testDistrictTranslations.containsKey(key);
		}

		public String getTranslation(String key)
		{
			return testDistrictTranslations.get(key);
		}

		public String getTitleTranslation(int categoryCode, String defaultTitleTranslation)
		{
			final String titleTranslation;
			return titleTranslations == null ?
					defaultTitleTranslation :
					(titleTranslation = titleTranslations.get(categoryCode)) == null ?
							defaultTitleTranslation + " (translation doesn't exist)" :
							titleTranslation;
		}
	}

	private record RedAlertEvent(
			int cat,
			List<String> data,
			String desc,
			long id,
			String title
	)
	{
	}

	private record Configuration(
			boolean isMakeSound,
			boolean isAlertAll,
			boolean isDisplayResponse,
			boolean isShowTestAlerts,
			Duration timeout,
			LanguageCode languageCode,
			Level logLevel,
			Set<String> districtsOfInterest
	)
	{
	}

	private record District(
			String label,
			String value,
			int id,
			int areaid,
			String areaname,
			String label_he,
			int migun_time
	)
	{
	}

	private record TranslationAndProtectionTime(String translation, int protectionTime)
	{
		@Override
		public String toString()
		{
			return translation + ": " + protectionTime + " seconds";
		}
	}

	private static class LoggerLevelConverter implements CommandLine.ITypeConverter<Level>
	{
		@Override
		public Level convert(String s)
		{
			return Level.valueOf(s);
		}
	}
}
