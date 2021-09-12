package il.co.ashr123.redAlert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import il.co.ashr123.timeMesurment.DurationCounter;
import il.co.ashr123.timeMesurment.Result;
import il.co.ashr123.timeMesurment.TimeScales;
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
import javax.script.ScriptException;
import javax.sound.sampled.*;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
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
			Collections.emptySet()
	);
	private static final SimpleDateFormat DATE_FORMATTER_FOR_PRINTING = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS");
	@Option(names = {"-s", "--settings"},
			description = "Enter custom path to settings file.",
			defaultValue = "red-alert-settings.json")
	private final File settingsFile = new File("red-alert-settings.json");
	private Settings settings = DEFAULT_SETTINGS;
	private long settingsLastModified = 1;
	private Set<String> districtsNotFound = Collections.emptySet();
	/**
	 * Will be updated once a day from IDF's Home Front Command's server.
	 */
	private Map<String, String> districts;
	private boolean isContinue = true;

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
		System.out.println(JSON_MAPPER.writeValueAsString(loadRemoteDistricts(languageCode)));
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
		JSON_MAPPER.writeValue(file, loadRemoteDistricts(languageCode));
	}

	private static Map<String, String> loadRemoteDistricts(LanguageCode languageCode)
	{
		LOGGER.info("Getting remote districts from IDF's Home Front Command's server...");
		final Result<Map<String, String>> result = DurationCounter.measureAndExecute(() ->
		{
			while (true)
				try
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
//											LOGGER.debug("a: {}, b: {}", a, b);
											return b;
										}));
					LOGGER.warn("Didn't find translations for language: {}, returning empty dict", languageCode);
					return Map.of();
				} catch (ScriptException | IOException e)
				{
					LOGGER.error("Failed to get data for language {}: {}. Trying again...", languageCode, e.toString());
					if (e instanceof UnknownHostException || e instanceof ConnectException)
						sleep();
				}
		});
		LOGGER.info("Done (took {} seconds, got {} districts)", result.getTimeTaken(TimeScales.SECONDS), result.getResult().size());
		return result.getResult();
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
					.collect(Collectors.toSet());
			printDistrictsNotFoundWarning();
			setLoggerLevel(settings.logLevel());
		} else if (settingsLastModifiedTemp == 0 && settingsLastModified != 0)
		{
			LOGGER.warn("couldn't find \"{}\", using default settings", settingsFile);
			settings = DEFAULT_SETTINGS;
			if (districts == null || !oldLanguageCode.equals(settings.languageCode()))
				refreshDistrictsTranslationDicts();
			settingsLastModified = 0;
			districtsNotFound = Collections.emptySet();
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
					while (true)
						switch (scanner.nextLine())
						{
							case "q" -> {
								System.err.println("Bye Bye!");
								isContinue = false;
								return;
							}
							case "t" -> {
								System.err.println("Testing sound...");
								clip.setFramePosition(0);
								clip.start();
							}
							case "c" -> System.err.println("\033[H\033[2JListening...");
							case "r" -> refreshDistrictsTranslationDicts();
							case "h" -> printHelpMsg();
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
			final Set<String> testDistrict = Set.of("בדיקה");
			Set<String> prevData = Collections.emptySet();
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
							prevData = Collections.emptySet();
						else if ((lastModifiedStr = httpURLConnection.getHeaderField("last-modified")) == null ||
								(alertsLastModified = httpsDateParser.parse(lastModifiedStr)).after(currAlertsLastModified))
						{
							if (alertsLastModified != null)
								currAlertsLastModified = alertsLastModified;

							final RedAlertResponse redAlertResponse = JSON_MAPPER.readValue(httpURLConnection.getInputStream(), RedAlertResponse.class);
							LOGGER.debug("Original response content: {}", redAlertResponse);
							if (settings.isShowTestEvents() && redAlertResponse.data().equals(testDistrict))
							{
								System.out.println(redAlertEventToString(
										alertsLastModified,
										contentLength,
										settings.languageCode().getTestDistrictTranslation(),
										new StringBuilder("Test Event").append(System.lineSeparator()))
								);
								continue;
							}
							final Set<String>
									translatedData = redAlertResponse.data().parallelStream()
									.map(districts::get)
									.collect(Collectors.toSet()),
									importantDistricts = (translatedData.size() < settings.districtsOfInterest().size() ?
											translatedData.parallelStream()
													.filter(settings.districtsOfInterest()::contains) :
											settings.districtsOfInterest().parallelStream()
													.filter(translatedData::contains))
											.filter(Predicate.not(prevData::contains))
											.collect(Collectors.toSet());
							if (settings.isMakeSound() && (settings.isAlertAll() || !importantDistricts.isEmpty()))
							{
								clip.setFramePosition(0);
								clip.loop(settings.soundLoopCount());
							}
							final StringBuilder output = new StringBuilder();
							if (settings.isDisplayResponse())
								redAlertEventToString(
										alertsLastModified,
										contentLength,
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
					if (e instanceof UnknownHostException || e instanceof ConnectException)
						sleep();
				}
		} catch (UnsupportedAudioFileException | LineUnavailableException | IOException | NullPointerException e)
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

	private StringBuilder redAlertEventToString(Date alertsLastModified,
	                                            long contentLength,
	                                            Set<String> translatedData,
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
		HE(Set.of("בדיקה")),
		EN(Set.of("Test")),
		AR(Set.of("فحص")),
		RU(Set.of("осмотр"));
		private final Set<String> testDistrictTranslation;

		LanguageCode(Set<String> testDistrictTranslation)
		{
			this.testDistrictTranslation = testDistrictTranslation;
		}

		public Set<String> getTestDistrictTranslation()
		{
			return testDistrictTranslation;
		}
	}

	private static final record RedAlertResponse(
			Set<String> data,
			long id,
			String title
	)
	{
	}

	private static final record Settings(
			boolean isMakeSound,
			boolean isAlertAll,
			boolean isDisplayResponse,
			boolean isShowTestEvents,
			int connectTimeout,
			int readTimeout,
			int soundLoopCount,
			LanguageCode languageCode,
			Level logLevel,
			Set<String> districtsOfInterest
	)
	{
	}
}