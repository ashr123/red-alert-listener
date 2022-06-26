package io.github.ashr123.red.alert;

import com.fasterxml.jackson.core.type.TypeReference;
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
import picocli.CommandLine.Command;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Option;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Command(name = "red-alert-listener",
		mixinStandardHelpOptions = true,
		versionProvider = Listener.class,
		description = "An App that can get \"red alert\"s from IDF's Home Front Command.",
		showDefaultValues = true)
public class Listener implements Runnable, IVersionProvider
{
	private static final ZoneId DEFAULT_ZONE_ID = ZoneId.systemDefault();
	private static final TypeReference<List<District>> LIST_TYPE_REFERENCE = new TypeReference<>()
	{
	};
	private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(FixedDateFormat.FixedFormat.DEFAULT.getPattern());
	private static final Logger LOGGER = LogManager.getLogger();
	private static final ObjectMapper JSON_MAPPER = new JsonMapper().enable(SerializationFeature.INDENT_OUTPUT);
	private static final Settings DEFAULT_SETTINGS = new Settings(
			false,
			false,
			true,
			false,
			5000,
			10000,
			LanguageCode.HE,
			Level.INFO,
			Collections.emptySet()
	);
	private static volatile boolean isContinue = true;
	@Option(names = {"-s", "--settings"},
			description = "Enter custom path to settings file.",
			defaultValue = "red-alert-listener.conf.json")
	private final File settingsFile = new File("red-alert-listener.conf.json");
	private Settings settings = DEFAULT_SETTINGS;
	private long settingsLastModified = 1;
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

	@Command(mixinStandardHelpOptions = true,
			versionProvider = Listener.class,
			description = "Gets all supported districts translation from Hebrew from IDF's Home Front Command's server and print it to stdout (No need for configuration file).",
			showDefaultValues = true)
	private static void getRemoteDistrictsAsJSON(
			@Option(names = {"-l", "--language"},
					paramLabel = "language code",
					required = true,
					description = "Which language's translation to get? Valid values: ${COMPLETION-CANDIDATES} (case insensitive)")
			LanguageCode languageCode,
			@Option(names = {"-c", "--connect-timeout"},
					paramLabel = "connect timeout",
					defaultValue = "5000",
					description = "Connect timeout for connecting to IDF's Home Front Command's server.")
			int connectTimeout,
			@Option(names = {"-r", "--read-timeout"},
					paramLabel = "read timeout",
					defaultValue = "10000",
					description = "Read timeout for connecting to IDF's Home Front Command's server.")
			int readTimeout,
			@Option(names = {"-L", "--logger-level"},
					paramLabel = "logger level",
					defaultValue = "INFO",
					converter = LevelConverter.class,
					description = "Level of logger. Valid values: OFF, FATAL, ERROR, WARN, INFO, DEBUG, TRACE, ALL (case insensitive).")
			Level loggerLevel
	) throws IOException, InterruptedException
	{
		try (InputStream ignored = System.in)
		{
			System.out.println(JSON_MAPPER.writeValueAsString(startSubcommandInputThread(
					languageCode,
					connectTimeout,
					readTimeout,
					loggerLevel,
					District::label
			)));
		}
	}

	@Command(mixinStandardHelpOptions = true,
			versionProvider = Listener.class,
			description = "Gets all supported districts translation from Hebrew from IDF's Home Front Command's server and print it to file (No need for configuration file).",
			showDefaultValues = true)
	private static void getRemoteDistrictsAsJSONToFile(
			@Option(names = {"-o", "--output"},
					paramLabel = "file",
					defaultValue = "districts.json",
					description = "Where to save received districts.")
			File file,
			@Option(names = {"-l", "--language"},
					paramLabel = "language code",
					required = true,
					description = "Which language's translation to get? Valid values: ${COMPLETION-CANDIDATES} (case insensitive)")
			LanguageCode languageCode,
			@Option(names = {"-c", "--connect-timeout"},
					paramLabel = "connect timeout",
					defaultValue = "5000",
					description = "Connect timeout for connecting to IDF's Home Front Command's server.")
			int connectTimeout,
			@Option(names = {"-r", "--read-timeout"},
					paramLabel = "read timeout",
					defaultValue = "10000",
					description = "Read timeout for connecting to IDF's Home Front Command's server.")
			int readTimeout,
			@Option(names = {"-L", "--logger-level"},
					paramLabel = "logger level",
					defaultValue = "INFO",
					converter = LevelConverter.class,
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
							connectTimeout,
							readTimeout,
							loggerLevel,
							District::label
					)
			);
		}
	}

	private static <T> Map<String, T> startSubcommandInputThread(
			LanguageCode languageCode,
			int connectTimeout,
			int readTimeout,
			Level level,
			Function<District, T> districtMapper
	) throws InterruptedException
	{
		final CountDownLatch startSignal = new CountDownLatch(1);
		new Thread(() ->
		{
			try (Scanner scanner = new Scanner(System.in))
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
		return loadRemoteDistricts(languageCode, connectTimeout, readTimeout, districtMapper);
	}

	private static <T> Map<String, T> loadRemoteDistricts(
			LanguageCode languageCode,
			int connectTimeout,
			int readTimeout,
			Function<District, T> districtMapper
	)
	{
		LOGGER.info("Getting remote districts from IDF's Home Front Command's server...");
		while (isContinue)
			try
			{
				final Result<Map<String, T>> result = TimeMeasurement.measureAndExecuteCallable(() ->
				{
					if (new URL("https://www.oref.org.il/Shared/Ajax/GetDistricts.aspx?lang=" + languageCode.name().toLowerCase()).openConnection() instanceof HttpURLConnection httpURLConnection)
					{
						httpURLConnection.setRequestProperty("Accept", "application/json");
						httpURLConnection.setConnectTimeout(connectTimeout);
						httpURLConnection.setReadTimeout(readTimeout);
						httpURLConnection.setUseCaches(false);
						return JSON_MAPPER.readValue(httpURLConnection.getInputStream(), LIST_TYPE_REFERENCE)
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
					} else
						LOGGER.error("Not a HTTP connection, returning empty map");
					return Map.of();
				});
				LOGGER.info("Done (took {} seconds, got {} districts)", result.getTimeTaken(TimeScales.SECONDS), result.getResult().size());
				return result.getResult();
			} catch (Exception e)
			{
				LOGGER.debug("Failed to get data for language code {}: {}. Trying again...", languageCode, e.toString());
				if (e instanceof IOException)
					sleep();
			}
		return Map.of();
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

	private void loadSettings() throws IOException
	{
		final long settingsLastModifiedTemp = settingsFile.lastModified();
		final LanguageCode oldLanguageCode = settings.languageCode();
		if (settingsLastModifiedTemp > settingsLastModified)
		{
			LOGGER.info("(re)loading settings from file \"{}\"", settingsFile);
			settings = JSON_MAPPER.readValue(settingsFile, Settings.class);
			settingsLastModified = settingsLastModifiedTemp;
			if (districts == null || !oldLanguageCode.equals(settings.languageCode()))
				refreshDistrictsTranslation();
			districtsNotFound = (settings.districtsOfInterest().size() > 2 ?
					new ArrayList<>(settings.districtsOfInterest()) :
					settings.districtsOfInterest()).parallelStream().unordered()
					.filter(Predicate.not(getTranslationFromTranslationAndProtectionTime(new ArrayList<>(districts.values()))::contains))
					.toList();
			printDistrictsNotFoundWarning();
			setLoggerLevel(settings.logLevel());
		} else if (settingsLastModifiedTemp == 0 && settingsLastModified != 0)
		{
			LOGGER.warn("couldn't find \"{}\", using default settings", settingsFile);
			settings = DEFAULT_SETTINGS;
			if (districts == null || !oldLanguageCode.equals(settings.languageCode()))
				refreshDistrictsTranslation();
			settingsLastModified = 0;
			districtsNotFound = Collections.emptyList();
			setLoggerLevel(settings.logLevel());
		}
	}

	@Override
	public void run()
	{
		System.err.println("Preparing Red Alert Listener v" + getClass().getPackage().getImplementationVersion() + "...");
		final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
		HttpURLConnection httpURLConnectionField = null;
		try (Clip clip = AudioSystem.getClip(Stream.of(AudioSystem.getMixerInfo()).parallel()
				.filter(mixerInfo -> mixerInfo.getName().equals("default [default]"))
				.findAny()
				.orElse(null));
			 AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(new BufferedInputStream(Objects.requireNonNull(getClass().getResourceAsStream("/alarmSound.wav"))));
			 InputStream in = System.in)
		{
			clip.open(audioInputStream);
			new Thread(() ->
			{
				try (Scanner scanner = new Scanner(in))
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
			loadSettings();
			final URL url = new URL("https://www.oref.org.il/WarningMessages/alert/alerts.json");
			Set<String> prevData = Collections.emptySet();
			ZonedDateTime currAlertsLastModified = LocalDateTime.MIN.atZone(ZoneId.of("Z"));
			final int minRedAlertEventStrLength = """
					{"cat":"1","data":[],"desc":"","id":0,"title":""}""".getBytes(StandardCharsets.UTF_8).length;
			final double alarmSoundSecondLength = clip.getMicrosecondLength() / 1E6;
			System.err.println("Listening...");
			while (isContinue)
				try
				{
					if (url.openConnection() instanceof HttpURLConnection httpURLConnection)
					{
						loadSettings();
						httpURLConnectionField = httpURLConnection;
						httpURLConnection.setRequestProperty("Accept", "application/json"); // Not mandatory, but it's a good practice
						httpURLConnection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
						httpURLConnection.setRequestProperty("Referer", "https://www.oref.org.il/12481-" + settings.languageCode().name().toLowerCase() + "/Pakar.aspx");
						httpURLConnection.setConnectTimeout(settings.connectTimeout());
						httpURLConnection.setReadTimeout(settings.readTimeout());
						httpURLConnection.setUseCaches(false);

						if (httpURLConnection.getResponseCode() != HttpURLConnection.HTTP_OK/* &&
								httpURLConnection.getResponseCode() != HttpURLConnection.HTTP_NOT_MODIFIED*/)
						{
							LOGGER.debug("Connection response: {}", httpURLConnection.getResponseMessage());
							sleep();
							continue;
						}
						ZonedDateTime alertsLastModified = null;
						final long contentLength = httpURLConnection.getContentLengthLong();
						final String lastModifiedStr;
						if (contentLength < minRedAlertEventStrLength)
							prevData = Collections.emptySet();
						else if ((lastModifiedStr = httpURLConnection.getHeaderField("last-modified")) == null ||
								(alertsLastModified = ZonedDateTime.parse(lastModifiedStr, DateTimeFormatter.RFC_1123_DATE_TIME)).isAfter(currAlertsLastModified))
						{
							if (alertsLastModified != null)
								currAlertsLastModified = alertsLastModified;

							final RedAlertEvent redAlertEvent = JSON_MAPPER.readValue(httpURLConnection.getInputStream(), RedAlertEvent.class);
							LOGGER.debug("Original event data: {}", redAlertEvent);
							// TODO rethink of what defines a drill alert
							if (redAlertEvent.data().parallelStream().unordered()
									.allMatch(LanguageCode.HE::containsKey))
							{
								if (settings.isShowTestAlerts())
									System.out.println(redAlertToString(
											contentLength,
											alertsLastModified,
											redAlertEvent,
											redAlertEvent.data().parallelStream().unordered()
													.map(settings.languageCode()::getTranslation)
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
									.toList(), // to know if new (unseen) districts were added from previous request
									newDistrictsOfInterest = unseenTranslatedDistricts.parallelStream().unordered()
											.filter(translationAndProtectionTime -> settings.districtsOfInterest().contains(translationAndProtectionTime.translation()))
											.toList(); // for not restarting alert sound unnecessarily

							if (settings.isMakeSound() && (settings.isAlertAll() || !newDistrictsOfInterest.isEmpty()))
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
							if (settings.isDisplayResponse() && !unseenTranslatedStrings.isEmpty())
								redAlertToString(
										contentLength,
										alertsLastModified,
										redAlertEvent,
										unseenTranslatedStrings,
										output
								);

							if (!newDistrictsOfInterest.isEmpty())
								output.append("ALERT: ").append(newDistrictsOfInterest).append(System.lineSeparator());
							if (!output.isEmpty())
								System.out.println(output);

							printDistrictsNotFoundWarning();
							prevData = getTranslationFromTranslationAndProtectionTime(translatedData);
						}
					} else
						LOGGER.error("Not a HTTP connection!");
				} catch (IOException e)
				{
					LOGGER.debug("Got exception: {}", e.toString());
					sleep();
				}
		} catch (Throwable e)
		{
			LOGGER.fatal("Closing connection and exiting...", e);
		} finally
		{
			scheduledExecutorService.shutdownNow();
			if (httpURLConnectionField != null)
				httpURLConnectionField.disconnect();
		}
	}

	private Set<String> getTranslationFromTranslationAndProtectionTime(List<TranslationAndProtectionTime> translatedData)
	{
		return translatedData.parallelStream().unordered()
				.filter(Objects::nonNull)
				.map(TranslationAndProtectionTime::translation)
				.collect(Collectors.toSet());
	}

	private List<TranslationAndProtectionTime> getTranslatedData(RedAlertEvent redAlertEvent)
	{
		return redAlertEvent.data().parallelStream().unordered()
				.map(districts::get)
				.toList();
	}

	private StringBuilder redAlertToString(long contentLength,
										   ZonedDateTime alertsLastModified,
										   RedAlertEvent redAlertEvent,
										   Collection<String> translatedData,
										   StringBuilder output)
	{
		return output.append("Translated title: ").append(settings.languageCode().getTitleTranslation(redAlertEvent.cat(), redAlertEvent.title())).append(System.lineSeparator())
				.append("Content Length: ").append(contentLength).append(" bytes").append(System.lineSeparator())
				.append("Last Modified Date: ").append(alertsLastModified == null ? null : DATE_TIME_FORMATTER.format(alertsLastModified.withZoneSameInstant(DEFAULT_ZONE_ID))).append(System.lineSeparator())
				.append("Current Date: ").append(DATE_TIME_FORMATTER.format(ZonedDateTime.now())).append(System.lineSeparator())
				.append("Translated districts: ").append(translatedData).append(System.lineSeparator());
	}

	private void refreshDistrictsTranslation()
	{
		districts = loadRemoteDistricts(
				settings.languageCode(),
				settings.connectTimeout(),
				settings.readTimeout(),
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
		private final Map<String, String> testDistrictTranslation;
		private final Map<Integer, String> titleTranslations;

		LanguageCode(Map<String, String> testDistrictTranslation, Map<Integer, String> titleTranslations)
		{
			this.testDistrictTranslation = testDistrictTranslation;
			this.titleTranslations = titleTranslations;
		}

		public boolean containsKey(String key)
		{
			return testDistrictTranslation.containsKey(key);
		}

		public String getTranslation(String key)
		{
			return testDistrictTranslation.get(key);
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

	private record Settings(
			boolean isMakeSound,
			boolean isAlertAll,
			boolean isDisplayResponse,
			boolean isShowTestAlerts,
			int connectTimeout,
			int readTimeout,
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

	private static class LevelConverter implements ITypeConverter<Level>
	{
		@Override
		public Level convert(String s)
		{
			return Level.valueOf(s);
		}
	}
}
