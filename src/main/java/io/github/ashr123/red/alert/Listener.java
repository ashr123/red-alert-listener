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
			defaultValue = "red-alert-listener-settings.json")
	private final File settingsFile = new File("red-alert-listener-settings.json");
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
			description = "Gets all supported districts translation from Hebrew from IDF's Home Front Command's server and print it to stdout.",
			showDefaultValues = true)
	private static void getRemoteDistrictsAsJSON(
			@Option(names = {"-l", "--language"},
					paramLabel = "language code",
					required = true,
					description = "Which language's translation to get? Valid values: ${COMPLETION-CANDIDATES} (case insensitive)")
					LanguageCode languageCode) throws IOException, InterruptedException
	{
		try (InputStream ignored = System.in)
		{
			startSubcommandInputThread();
			System.out.println(JSON_MAPPER.writeValueAsString(loadRemoteDistricts(languageCode, District::label)));
		}
	}

	@Command(mixinStandardHelpOptions = true,
			versionProvider = Listener.class,
			description = "Gets all supported districts translation from Hebrew from IDF's Home Front Command's server and print it to file.",
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
					LanguageCode languageCode) throws IOException, InterruptedException
	{
		try (InputStream ignored = System.in)
		{
			startSubcommandInputThread();
			JSON_MAPPER.writeValue(file, loadRemoteDistricts(languageCode, District::label));
		}
	}

	private static void startSubcommandInputThread() throws InterruptedException
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
			} catch (NoSuchElementException ignored)
			{
			}
		}).start();
		startSignal.await();
	}

	private static <T> Map<String, T> loadRemoteDistricts(LanguageCode languageCode, Function<District, T> districtMapper)
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
						return JSON_MAPPER.readValue(httpURLConnection.getInputStream(), LIST_TYPE_REFERENCE).parallelStream()
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
				refreshDistrictsTranslationDicts();
			districtsNotFound = (settings.districtsOfInterest().size() > 2 ?
					new ArrayList<>(settings.districtsOfInterest()) :
					settings.districtsOfInterest()).parallelStream()
					.filter(Predicate.not(districts.values().parallelStream()
							.map(TranslationAndProtectionTime::translation)
							.collect(Collectors.toSet())::contains))
					.collect(Collectors.toList());
			printDistrictsNotFoundWarning();
			setLoggerLevel(settings.logLevel());
		} else if (settingsLastModifiedTemp == 0 && settingsLastModified != 0)
		{
			LOGGER.warn("couldn't find \"{}\", using default settings", settingsFile);
			settings = DEFAULT_SETTINGS;
			if (districts == null || !oldLanguageCode.equals(settings.languageCode()))
				refreshDistrictsTranslationDicts();
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
							case "" -> {
							}
							case "q", "quit", "exit" -> isContinue = false;
							case "t", "test", "test-sound" -> {
								System.err.println("Testing sound...");
								clip.setFramePosition(0);
								clip.start();
							}
							case "c", "clear" -> System.err.println("\033[H\033[2JListening...");
							case "r", "refresh", "refresh-districts" -> refreshDistrictsTranslationDicts();
							case "h", "help" -> printHelpMsg();
							default -> {
								System.err.println("Unrecognized command!");
								printHelpMsg();
							}
						}
				} catch (NoSuchElementException ignored)
				{
				}
				System.err.println("Bye Bye!");
			}).start();
			scheduledExecutorService.scheduleAtFixedRate(this::refreshDistrictsTranslationDicts, 1, 1, TimeUnit.DAYS);
			loadSettings();
			final Map<Integer, Map<LanguageCode, String>> catVsAlertNames = Map.ofEntries(
					Map.entry(1, Map.ofEntries(
							Map.entry(LanguageCode.EN, "Rocket and missile fire"),
							Map.entry(LanguageCode.RU, "Ракетный обстрел"),
							Map.entry(LanguageCode.AR, "اطلاق قذائف وصواريخ")
					)),
					//missing 2
					Map.entry(3, Map.ofEntries(
							Map.entry(LanguageCode.EN, "Earthquake"),
							Map.entry(LanguageCode.RU, "Землетрясение"),
							Map.entry(LanguageCode.AR, "هزّة أرضية")
					)),
					Map.entry(4, Map.ofEntries(
							Map.entry(LanguageCode.EN, "Radiological event"),
							Map.entry(LanguageCode.RU, "Радиоактивная опасность"),
							Map.entry(LanguageCode.AR, "حدث إشعاعي")
					)),
					Map.entry(5, Map.ofEntries(
							Map.entry(LanguageCode.EN, "Fear of a tsunami"),
							Map.entry(LanguageCode.RU, "Угроза цунами"),
							Map.entry(LanguageCode.AR, "تحسبا للتسونامي")
					)),
					Map.entry(6, Map.ofEntries(
							Map.entry(LanguageCode.EN, "Hostile aircraft intrusion"),
							Map.entry(LanguageCode.RU, "Нарушение воздушного пространства"),
							Map.entry(LanguageCode.AR, "اختراق طائرة معادية")
					)),
					Map.entry(7, Map.ofEntries(
							Map.entry(LanguageCode.EN, "Hazardous Materials Event"),
							Map.entry(LanguageCode.RU, "Утечка опасных веществ"),
							Map.entry(LanguageCode.AR, "حدث مواد خطرة")
					)),
					// missing 10
					Map.entry(13, Map.ofEntries(
							Map.entry(LanguageCode.EN, "Terrorist infiltration"),
							Map.entry(LanguageCode.RU, "Проникновение террористов"),
							Map.entry(LanguageCode.AR, "تسلل مخربين")
					)),
					Map.entry(101, Map.ofEntries(
							Map.entry(LanguageCode.EN, "Rocket and missile fire drill"),
							Map.entry(LanguageCode.RU, "Учения по ракетному обстрелу"),
							Map.entry(LanguageCode.AR, "تمرين اطلاق قذائف وصواريخ")
					)),
					// missing 102
					Map.entry(103, Map.ofEntries(
							Map.entry(LanguageCode.EN, "Earthquake drill"),
							Map.entry(LanguageCode.RU, "Учения на случай землетрясения"),
							Map.entry(LanguageCode.AR, "تمرين هزّة أرضية")
					)),
					// missing 104
					Map.entry(105, Map.ofEntries(
							Map.entry(LanguageCode.EN, "Tsunami drill"),
							Map.entry(LanguageCode.RU, "Учения на случай цунами"),
							Map.entry(LanguageCode.AR, "تمرين تسونامي")
					)),
					// missing 106
					Map.entry(107, Map.ofEntries(
							Map.entry(LanguageCode.EN, "Hazardous Materials drill"),
							Map.entry(LanguageCode.RU, "Учения на случай утечки опасных веществ"),
							Map.entry(LanguageCode.AR, "تمرين مواد خطرة")
					)),
					// missing 110
					Map.entry(113, Map.ofEntries(
							Map.entry(LanguageCode.EN, "Terrorist infiltration drill"),
							Map.entry(LanguageCode.RU, "Учения на случай проникновения террористов"),
							Map.entry(LanguageCode.AR, "تمرين تسلل مخربين")
					))
			);
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
							if (redAlertEvent.data().equals(LanguageCode.HE.getTestDistrictTranslation()))
							{
								if (settings.isShowTestAlerts())
									System.out.println(redAlertToString(
											contentLength,
											alertsLastModified,
											redAlertEvent,
											catVsAlertNames,
											settings.languageCode().getTestDistrictTranslation(),
											new StringBuilder("Test Alert").append(System.lineSeparator())
									));
								continue;
							}

							List<TranslationAndProtectionTime> translatedData = getTranslatedData(redAlertEvent);

							Set<String> finalPrevData = prevData;
							final List<TranslationAndProtectionTime> newDistrictsOfInterest = translatedData.parallelStream()
									.filter(translationAndProtectionTime -> settings.districtsOfInterest().contains(translationAndProtectionTime.translation()))
									.filter(translationAndProtectionTime -> !finalPrevData.contains(translationAndProtectionTime.translation()))
									.toList();

							if (translatedData.contains(null))
							{
								LOGGER.warn("There is at least one district that couldn't be translated, refreshing districts translations from server...");
								refreshDistrictsTranslationDicts();
								translatedData = getTranslatedData(redAlertEvent);
							}
							if (settings.isMakeSound() && (settings.isAlertAll() || !newDistrictsOfInterest.isEmpty()))
								newDistrictsOfInterest.parallelStream()
										.mapToInt(TranslationAndProtectionTime::protectionTime)
										.max()
										.ifPresent(maxProtectionTime ->
										{
											clip.setFramePosition(0);
											clip.loop(Math.max(1, (int) Math.round(maxProtectionTime / alarmSoundSecondLength)));
										});
							final Set<String> translatedDistricts = translatedData.parallelStream()
									.map(TranslationAndProtectionTime::translation)
									.collect(Collectors.toSet());
							final StringBuilder output = new StringBuilder();
							if (settings.isDisplayResponse())
								redAlertToString(
										contentLength,
										alertsLastModified,
										redAlertEvent,
										catVsAlertNames,
										translatedDistricts,
										output
								);

							if (!newDistrictsOfInterest.isEmpty())
								output.append("ALERT: ").append(newDistrictsOfInterest).append(System.lineSeparator());
							if (!output.isEmpty())
								System.out.println(output);

							printDistrictsNotFoundWarning();
							prevData = translatedDistricts;
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
			LOGGER.fatal("Fatal error: {}. Closing connection and exiting...", e.toString());
		} finally
		{
			scheduledExecutorService.shutdownNow();
			if (httpURLConnectionField != null)
				httpURLConnectionField.disconnect();
		}
	}

	private List<TranslationAndProtectionTime> getTranslatedData(RedAlertEvent redAlertEvent)
	{
		return redAlertEvent.data().parallelStream()
				.map(districts::get)
				.collect(Collectors.toList());
	}

	private StringBuilder redAlertToString(long contentLength,
										   ZonedDateTime alertsLastModified,
										   RedAlertEvent redAlertEvent,
										   Map<Integer, Map<LanguageCode, String>> catVsAlertNames,
										   Collection<String> translatedData,
										   StringBuilder output)
	{
		final Map<LanguageCode, String> titleTranslation;
		return output.append("Title: ").append(settings.languageCode().equals(LanguageCode.HE) ?
						redAlertEvent.title() :
						(titleTranslation = catVsAlertNames.get(redAlertEvent.cat())) == null ?
								redAlertEvent.title() + " (translation doesn't exist)" :
								titleTranslation.get(settings.languageCode())).append(System.lineSeparator())
				.append("Content Length: ").append(contentLength).append(" bytes").append(System.lineSeparator())
				.append("Last Modified Date: ").append(alertsLastModified == null ? null : DATE_TIME_FORMATTER.format(alertsLastModified.withZoneSameInstant(DEFAULT_ZONE_ID))).append(System.lineSeparator())
				.append("Current Date: ").append(DATE_TIME_FORMATTER.format(ZonedDateTime.now())).append(System.lineSeparator())
				.append("Translated districts: ").append(translatedData).append(System.lineSeparator());
	}

	private void refreshDistrictsTranslationDicts()
	{
		districts = loadRemoteDistricts(settings.languageCode(), district -> new TranslationAndProtectionTime(district.label(), district.migun_time()));
	}

	@SuppressWarnings("unused")
	private enum LanguageCode
	{
		HE(List.of("בדיקה")),
		EN(List.of("Test")),
		AR(List.of("فحص")),
		RU(List.of("Проверка"));
		private final List<String> testDistrictTranslation;

		LanguageCode(List<String> testDistrictTranslation)
		{
			this.testDistrictTranslation = testDistrictTranslation;
		}

		public List<String> getTestDistrictTranslation()
		{
			return testDistrictTranslation;
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
}
