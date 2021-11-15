package io.github.ashr123.redAlert;

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
import org.jsoup.Jsoup;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Option;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Command(name = "red-alert",
		mixinStandardHelpOptions = true,
		versionProvider = RedAlert.class,
		description = "An App that can get \"red alert\"s from IDF's Home Front Command.",
		showDefaultValues = true)
public class RedAlert implements Callable<Integer>, IVersionProvider
{
	private static final Logger LOGGER = LogManager.getLogger();
	@SuppressWarnings("RegExpRedundantEscape")
	private static final Pattern PATTERN = Pattern.compile("(?:var|let|const)\\s+districts\\s*=\\s*(\\[.*\\])", Pattern.DOTALL);
	private static final ScriptEngine JS = new ScriptEngineManager().getEngineByName("javascript");
	private static final ObjectMapper JSON_MAPPER = new JsonMapper().enable(SerializationFeature.INDENT_OUTPUT);
	private static final Settings DEFAULT_SETTINGS = new Settings(
			false,
			false,
			true,
			false,
			5000,
			10000,
			15,
			LanguageCode.HE,
			Level.INFO,
			Collections.emptyList()
	);
	private static final SimpleDateFormat DATE_FORMATTER_FOR_PRINTING = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS");
	private static boolean isContinue = true;
	@Option(names = {"-s", "--settings"},
			description = "Enter custom path to settings file.",
			defaultValue = "red-alert-settings.json")
	private final File settingsFile = new File("red-alert-settings.json");
	private Settings settings = DEFAULT_SETTINGS;
	private long settingsLastModified = 1;
	private List<String> districtsNotFound = Collections.emptyList();
	/**
	 * Will be updated once a day from IDF's Home Front Command's server.
	 */
	private Map<String, String> districts;

	public static void main(String... args)
	{
		new CommandLine(RedAlert.class).setCaseInsensitiveEnumValuesAllowed(true).execute(args);
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
			versionProvider = RedAlert.class,
			description = "Gets all supported districts translation from Hebrew from IDF's Home Front Command's server and print it to stdout.",
			showDefaultValues = true)
	private static void getRemoteDistrictsAsJSON(
			@Option(names = {"-l", "--language"},
					paramLabel = "language code",
					required = true,
					description = "Which language's translation to get? Valid values: ${COMPLETION-CANDIDATES} (case insensitive)")
					LanguageCode languageCode) throws IOException
	{
		try
		{
			startSubcommandInputThread();
			System.out.println(JSON_MAPPER.writeValueAsString(loadRemoteDistricts(languageCode)));
		} finally
		{
			System.in.close();
		}
	}

	@Command(mixinStandardHelpOptions = true,
			versionProvider = RedAlert.class,
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
					LanguageCode languageCode) throws IOException
	{
		try
		{
			startSubcommandInputThread();
			JSON_MAPPER.writeValue(file, loadRemoteDistricts(languageCode));
		} finally
		{
			System.in.close();
		}
	}

	private static void startSubcommandInputThread()
	{
		new Thread(() ->
		{
			try (Scanner scanner = new Scanner(System.in))
			{
				System.err.println("Enter \"q\" to quit");
				while (isContinue)
					switch (scanner.nextLine().trim())
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
	}

	private static Map<String, String> loadRemoteDistricts(LanguageCode languageCode)
	{
		LOGGER.info("Getting remote districts from IDF's Home Front Command's server...");
		while (isContinue)
			try
			{
				final Result<Map<String, String>> result = TimeMeasurement.measureAndExecuteCallable(() ->
				{
					final Matcher script = PATTERN.matcher(Jsoup.connect("https://www.oref.org.il/12481-" + languageCode.name().toLowerCase() + "/Pakar.aspx")
							.get()
							.select("script:containsData(districts)")
							.html());
					if (script.find())
						return ((Bindings) JS.eval(script.group(1)))
								.values().parallelStream()
								.map(Bindings.class::cast)
								.collect(Collectors.toMap(
										scriptObjectMirror -> scriptObjectMirror.get("label_he").toString(),
										scriptObjectMirror -> scriptObjectMirror.get("label").toString(),
										(a, b) ->
										{
											LOGGER.trace("a: {}, b: {}", a, b);
											return b;
										}));
					LOGGER.warn("Didn't find translations for language: {}, returning empty dict", languageCode);
					return Map.of();
				});
				LOGGER.info("Done (took {} seconds, got {} districts)", result.getTimeTaken(TimeScales.SECONDS), result.getResult().size());
				return result.getResult();
			} catch (Exception e)
			{
				LOGGER.error("Failed to get data for language {}: {}. Trying again...", languageCode, e.toString());
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
			districtsNotFound = settings.districtsOfInterest().parallelStream()
					.filter(Predicate.not(new HashSet<>(districts.values())::contains))
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
	public Integer call() throws IOException
	{
		System.err.println("Preparing Red Alert Listener v" + getClass().getPackage().getImplementationVersion() + "...");
		final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
		HttpURLConnection httpURLConnectionField = null;
		try (Clip clip = AudioSystem.getClip();
		     AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(new BufferedInputStream(Objects.requireNonNull(getClass().getResourceAsStream("/alarmSound.wav")))))
		{
			clip.open(audioInputStream);
			new Thread(() ->
			{
				try (Scanner scanner = new Scanner(System.in))
				{
					printHelpMsg();
					while (isContinue)
						switch (scanner.nextLine().trim())
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
			final URL url = new URL("https://www.oref.org.il/WarningMessages/alert/alerts.json");
			final SimpleDateFormat httpsDateParser = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
			List<String> prevData = Collections.emptyList();
			Date currAlertsLastModified = Date.from(Instant.EPOCH);
			System.err.println("Listening...");
			while (isContinue)
				try
				{
					if (url.openConnection() instanceof HttpURLConnection httpURLConnection)
					{
						loadSettings();
						httpURLConnectionField = httpURLConnection;
						httpURLConnection.setRequestProperty("Referer", "https://www.oref.org.il/12481-" + settings.languageCode().name().toLowerCase() + "/Pakar.aspx");
						httpURLConnection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
						httpURLConnection.setConnectTimeout(settings.connectTimeout());
						httpURLConnection.setReadTimeout(settings.readTimeout());
						httpURLConnection.setUseCaches(false);

						if (httpURLConnection.getResponseCode() != HttpURLConnection.HTTP_OK)
						{
							LOGGER.error("Connection response {}", httpURLConnection.getResponseMessage());
							sleep();
							continue;
						}
						Date alertsLastModified = null;
						final long contentLength = httpURLConnection.getContentLengthLong();
						final String lastModifiedStr;
						if (contentLength == 0)
							prevData = Collections.emptyList();
						else if ((lastModifiedStr = httpURLConnection.getHeaderField("last-modified")) == null ||
								(alertsLastModified = httpsDateParser.parse(lastModifiedStr)).after(currAlertsLastModified))
						{
							if (alertsLastModified != null)
								currAlertsLastModified = alertsLastModified;

							final RedAlertEvent redAlertEvent = JSON_MAPPER.readValue(httpURLConnection.getInputStream(), RedAlertEvent.class);
							LOGGER.debug("Original event data: {}", redAlertEvent);
							if (settings.isShowTestAlerts() && redAlertEvent.data().equals(LanguageCode.HE.getTestDistrictTranslation()))
							{
								System.out.println(redAlertToString(
										contentLength,
										alertsLastModified,
										settings.languageCode().getTestDistrictTranslation(),
										new StringBuilder("Test Alert").append(System.lineSeparator())
								));
								continue;
							}
							List<String> translatedData = getTranslatedData(redAlertEvent);
							final List<String> importantDistricts = (translatedData.size() < settings.districtsOfInterest().size() ?
									translatedData.parallelStream()
											.filter((settings.districtsOfInterest().size() > 1 ? new HashSet<>(settings.districtsOfInterest()) : settings.districtsOfInterest())::contains) :
									settings.districtsOfInterest().parallelStream()
											.filter((translatedData.size() > 1 ? new HashSet<>(translatedData) : translatedData)::contains))
									.filter(Predicate.not(prevData::contains))
									.collect(Collectors.toList());
							if (translatedData.contains(null))
							{
								LOGGER.warn("There is at least one district that couldn't be translated, refreshing districts translations from server...");
								refreshDistrictsTranslationDicts();
								translatedData = getTranslatedData(redAlertEvent);
							}
							if (settings.isMakeSound() && (settings.isAlertAll() || !importantDistricts.isEmpty()))
							{
								clip.setFramePosition(0);
								clip.loop(settings.soundLoopCount());
							}
							final StringBuilder output = new StringBuilder();
							if (settings.isDisplayResponse())
								redAlertToString(
										contentLength,
										alertsLastModified,
										translatedData,
										output
								);

							if (!importantDistricts.isEmpty())
								output.append("ALERT: ").append(importantDistricts).append(System.lineSeparator());
							if (!output.isEmpty())
								System.out.println(output);

							printDistrictsNotFoundWarning();
							prevData = translatedData;
						}
					} else
						LOGGER.error("Not a HTTP connection!");
				} catch (IOException | ParseException e)
				{
					LOGGER.error("Got exception: {}", e.toString());
					if (e instanceof IOException)
						sleep();
				}
		} catch (Throwable e)
		{
			LOGGER.fatal("Fatal error: {}. Closing connection end exiting...", e.toString());
			return 1;
		} finally
		{
			scheduledExecutorService.shutdownNow();
			if (httpURLConnectionField != null)
				httpURLConnectionField.disconnect();
			System.in.close();
		}
		return 0;
	}

	private List<String> getTranslatedData(RedAlertEvent redAlertEvent)
	{
		return redAlertEvent.data().parallelStream()
				.map(districts::get)
				.collect(Collectors.toList());
	}

	private StringBuilder redAlertToString(long contentLength,
	                                       Date alertsLastModified,
	                                       List<String> translatedData,
	                                       StringBuilder output)
	{
		return output.append("Content Length: ").append(contentLength).append(" bytes").append(System.lineSeparator())
				.append("Last Modified Date: ").append(alertsLastModified == null ? null : DATE_FORMATTER_FOR_PRINTING.format(alertsLastModified)).append(System.lineSeparator())
				.append("Current Date: ").append(DATE_FORMATTER_FOR_PRINTING.format(new Date())).append(System.lineSeparator())
				.append("Translated districts: ").append(translatedData).append(System.lineSeparator());
	}

	private void refreshDistrictsTranslationDicts()
	{
		districts = loadRemoteDistricts(settings.languageCode());
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

	private static final record RedAlertEvent(
			List<String> data,
			long id,
			String title
	)
	{
	}

	private static final record Settings(
			boolean isMakeSound,
			boolean isAlertAll,
			boolean isDisplayResponse,
			boolean isShowTestAlerts,
			int connectTimeout,
			int readTimeout,
			int soundLoopCount,
			LanguageCode languageCode,
			Level logLevel,
			List<String> districtsOfInterest
	)
	{
	}
}