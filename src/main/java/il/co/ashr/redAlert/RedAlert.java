package il.co.ashr.redAlert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
	@SuppressWarnings("RegExpRedundantEscape")
	private static final Pattern PATTERN = Pattern.compile("(?:var|let|const) districts\\s*=\\s*(\\[.*\\])", Pattern.DOTALL);
	private static final ScriptEngine JS = new ScriptEngineManager().getEngineByName("javascript");
	private final static ObjectMapper OBJECT_MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
	private final Settings DEFAULT_SETTINGS = new Settings(
			false,
			false,
			true,
			false,
			5000,
			10000,
			15,
			Language.HE,
			Collections.emptySet()
	);
	@Option(names = {"-s", "--settings"},
			description = "Enter custom path to settings file.",
			defaultValue = "red-alert-settings.json")
	private final File settingsFile = new File("red-alert-settings.json");
	private final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
	private Settings settings;
	private long settingsLastModified = 1;
	private Set<String> districtsNotFound = Collections.emptySet();
	private HttpURLConnection httpURLConnectionField;
	/**
	 * Will be updated once a day from IDF's Home Front Command's server.
	 */
	private Map<String, String> districts;
	private boolean isContinue = true;

	public static void main(String... args)
	{
		new CommandLine(RedAlert.class).execute(args);
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
			interruptedException.printStackTrace();
		}
	}

	private static Map<String, String> loadRemoteDistricts(Language language)
	{
		System.err.println("Getting remote districts from IDF's Home Front Command's server...");
		while (true)
			try
			{
				final Matcher script = PATTERN.matcher(Jsoup.connect("https://www.oref.org.il/12481-" + language.name().toLowerCase() + "/Pakar.aspx")
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
//													System.err.println("a: " + a + ", b: " + b);
										return b;
									}));
				System.out.println("Warning: Didn't find translation for language: " + language + ", returning empty dict");
				return Map.of();
			} catch (ScriptException | IOException e)
			{
				System.err.println("Error: Failed to get data for language " + language + ": " + e + ". Trying again...");
			}
	}

	private void printDistrictsNotFoundWarning()
	{
		if (!districtsNotFound.isEmpty())
			System.err.println("Warning: those districts don't exist: " + districtsNotFound);
	}

	@Command(mixinStandardHelpOptions = true,
			versionProvider = RedAlert.class,
			description = "Gets all supported districts translation from Hebrew from IDF's Home Front Command's server and print it to stdout.",
			showDefaultValues = true)
	private static void getRemoteDistrictsAsJSON(
			@Option(names = {"-l", "--language"},
					paramLabel = "HE|EN|RU|AR",
					required = true,
					description = "Which language's translation to get?")
					Language language) throws IOException
	{
		System.out.println(OBJECT_MAPPER.writeValueAsString(loadRemoteDistricts(language)));
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
					paramLabel = "HE|EN|RU|AR",
					required = true,
					description = "Which language's translation to get?")
					Language language) throws IOException
	{
		OBJECT_MAPPER.writeValue(file, loadRemoteDistricts(language));
	}

	@Override
	public String[] getVersion()
	{
		return new String[]{"Red Alert Listener v" + RedAlert.class.getPackage().getImplementationVersion()};
	}

	private void loadSettings(File settingsFile) throws IOException
	{
		final long settingsLastModifiedTemp = settingsFile.lastModified();
		if (settingsLastModifiedTemp > settingsLastModified)
		{
			System.err.println("Info: (re)loading settings from file \"" + settingsFile + "\".");
			settings = OBJECT_MAPPER.readValue(settingsFile, Settings.class);
			refreshDistrictsTranslationDicts();
			settingsLastModified = settingsLastModifiedTemp;
			districtsNotFound = settings.districtsOfInterest().parallelStream()
					.filter(Predicate.not(new HashSet<>(districts.values())::contains))
					.collect(Collectors.toSet());
			printDistrictsNotFoundWarning();
		} else if (settingsLastModifiedTemp == 0 && settingsLastModified != 0)
		{
			System.err.println("Warning: couldn't find \"" + settingsFile + "\", using default settings");
			settings = DEFAULT_SETTINGS;
			settingsLastModified = 0;
			districtsNotFound = Collections.emptySet();
		}
	}

	@Override
	public Integer call()
	{
		System.err.println("Preparing Red Alert Listener v" + RedAlert.class.getPackage().getImplementationVersion() + "...");
		try (Clip clip = AudioSystem.getClip();
		     AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(new BufferedInputStream(Objects.requireNonNull(RedAlert.class.getResourceAsStream("/alarmSound.wav")))))
		{
			clip.open(audioInputStream);
			new Thread(() ->
			{
				final Scanner scanner = new Scanner(System.in);
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
						case "r" -> {
							refreshDistrictsTranslationDicts();
							System.err.println("Done");
						}
						case "h" -> printHelpMsg();
					}
			}).start();
			final URL url = new URL("https://www.oref.org.il/WarningMessages/alert/alerts.json");
			final SimpleDateFormat SIMPLE_DATE_FORMAT = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
			scheduledExecutorService.scheduleAtFixedRate(this::refreshDistrictsTranslationDicts, 1, 1, TimeUnit.DAYS);
//			timer.scheduleAtFixedRate(task, 1000 * 60 * 60 * 24, 1000 * 60 * 60 * 24);
			loadSettings(settingsFile);
			Set<String> prevData = Collections.emptySet();
			Date currAlertsLastModified = Date.from(Instant.EPOCH);
			System.err.println("Listening...");
			while (isContinue)
				try
				{
					if (url.openConnection() instanceof HttpURLConnection httpURLConnection)
					{
						loadSettings(settingsFile);
						httpURLConnectionField = httpURLConnection;
						httpURLConnection.setRequestProperty("Referer", "https://www.oref.org.il/12481-" + settings.language().name().toLowerCase() + "/Pakar.aspx");
						httpURLConnection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
						httpURLConnection.setConnectTimeout(settings.connectTimeout());
						httpURLConnection.setReadTimeout(settings.readTimeout());
						httpURLConnection.setUseCaches(false);

						if (httpURLConnection.getResponseCode() != HttpURLConnection.HTTP_OK)
						{
							System.err.println("Error at " + new Date() + ": connection " + httpURLConnection.getResponseMessage());
							sleep();
							continue;
						}
						Date alertsLastModified = null;
						final long contentLength = httpURLConnection.getContentLengthLong();
						final String lastModifiedStr;
						if (contentLength == 0)
							prevData = Collections.emptySet();
						else if ((lastModifiedStr = httpURLConnection.getHeaderField("last-modified")) == null ||
								(alertsLastModified = SIMPLE_DATE_FORMAT.parse(lastModifiedStr)).after(currAlertsLastModified))
						{
							if (alertsLastModified != null)
								currAlertsLastModified = alertsLastModified;

							final RedAlertResponse redAlertResponse = OBJECT_MAPPER.readValue(httpURLConnection.getInputStream(), RedAlertResponse.class);
							final Set<String> translatedData = redAlertResponse.data().parallelStream()
									.map(districts::get)
									.collect(Collectors.toSet());

							final StringBuilder output = new StringBuilder();
							if (settings.isDisplayAll())
								output.append("Content Length: ").append(contentLength)
										.append(" bytes").append(System.lineSeparator())
										.append("Last Modified Date: ").append(alertsLastModified).append(System.lineSeparator())
										.append("Current Date: ").append(new Date()).append(System.lineSeparator())
										.append("Translated districts: ").append(translatedData).append(System.lineSeparator());
							if (settings.isDisplayOriginalResponseContent())
								output.append("Original response content: ").append(redAlertResponse).append(System.lineSeparator());

							printDistrictsNotFoundWarning();
							final Set<String> importantDistricts = (translatedData.size() > settings.districtsOfInterest().size() ?
									translatedData.parallelStream()
											.filter(settings.districtsOfInterest()::contains) :
									settings.districtsOfInterest().parallelStream()
											.filter(translatedData::contains))
									.filter(Predicate.not(prevData::contains))
									.collect(Collectors.toSet());
							prevData = translatedData;
							if (settings.isMakeSound() && (settings.isAlertAll() || !importantDistricts.isEmpty()))
							{
								clip.setFramePosition(0);
								clip.loop(settings.soundLoopCount());
							}
							if (!importantDistricts.isEmpty())
								output.append("ALERT: ").append(importantDistricts).append(System.lineSeparator());
							if (!output.isEmpty())
								System.out.println(output);
						}
					} else
						System.err.println("Error at " + new Date() + ": Not a HTTP connection!");
				} catch (IOException | ParseException e)
				{
					System.err.println("Exception at " + new Date() + ": " + e);
					if (e instanceof IOException)
						sleep();
				}
		} catch (UnsupportedAudioFileException | LineUnavailableException | IOException | NullPointerException e)
		{
			System.err.println("Fatal error at " + new Date() + ": " + e + System.lineSeparator() +
					"Closing connection end exiting...");
			return 1;
		} finally
		{
			scheduledExecutorService.shutdownNow();
			if (httpURLConnectionField != null)
				httpURLConnectionField.disconnect();
		}
		return 0;
	}

	private void refreshDistrictsTranslationDicts()
	{
		districts = loadRemoteDistricts(settings.language());
	}

	public static final record RedAlertResponse(
			Set<String> data,
			long id,
			String title
	)
	{
	}

	public static final record Settings(
			boolean isMakeSound,
			boolean isAlertAll,
			boolean isDisplayAll,
			boolean isDisplayOriginalResponseContent,
			int connectTimeout,
			int readTimeout,
			int soundLoopCount,
			Language language,
			Set<String> districtsOfInterest
	)
	{
	}
}