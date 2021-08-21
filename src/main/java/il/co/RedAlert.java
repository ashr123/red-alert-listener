package il.co;

import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class RedAlert
{
	private static final Settings DEFAULT_SETTINGS = new Settings(
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
	private static String settingsPath = "red-alert-settings.json";
	private static Settings settings;
	private static long settingsLastModified = 1;
	private static Set<String> districtsNotFound = Collections.emptySet();
	private static HttpURLConnection httpURLConnectionField;
	private static boolean isContinue = true;

	public static void main(String... args)
	{
		if (args.length > 0)
			switch (args[0])
			{
				case "-h", "--help" -> {
					System.out.printf("""
							Red Alert Listener v%s
							Options:
							  --help:                                            displays this help text and exits.
							  --settings-file-path <path/to/settings/file.json>: provide a path for a valid settings file, default path is "./red-alert-settings.json" (i.e. from current working directory).%n""", RedAlert.class.getPackage().getImplementationVersion());
					return;
				}
				case "--settings-file-path" -> {
					if (args.length > 1)
						settingsPath = args[1];
					else
						throw new IllegalStateException("Flag \"--settings-file-path\" must be followed by path to a legal settings file!");
				}
				default -> throw new IllegalStateException("Unexpected value: " + args[0]);
			}
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
						case "h" -> printHelpMsg();
					}
			}).start();
			final URL url = new URL("https://www.oref.org.il/WarningMessages/alert/alerts.json");
			final ObjectMapper objectMapper = new ObjectMapper();
			final SimpleDateFormat SIMPLE_DATE_FORMAT = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
			final File settingsFile = new File(settingsPath);
			final Districts districts = objectMapper.readValue(RedAlert.class.getResourceAsStream("/districts.json"), Districts.class);
			loadSettings(objectMapper, districts, settingsFile);
			Set<String> prevData = Collections.emptySet();
			Date currAlertsLastModified = Date.from(Instant.EPOCH);
			System.err.println("Listening...");
			while (isContinue)
				try
				{
					if (url.openConnection() instanceof HttpURLConnection httpURLConnection)
					{
						loadSettings(objectMapper, districts, settingsFile);
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

							final RedAlertResponse redAlertResponse = objectMapper.readValue(httpURLConnection.getInputStream(), RedAlertResponse.class);
							final Set<String> translatedData = redAlertResponse.data().parallelStream()
									.map(districts.getLanguage()::get)
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
			if (httpURLConnectionField != null)
				httpURLConnectionField.disconnect();
			System.exit(1);
		}
		if (httpURLConnectionField != null)
			httpURLConnectionField.disconnect();
	}

	private static void printHelpMsg()
	{
		System.err.println("Enter \"t\" for sound test, \"c\" for clearing the screen, \"q\" to quit or \"h\" for displaying this help massage.");
	}

	private static void printDistrictsNotFoundWarning()
	{
		if (!districtsNotFound.isEmpty())
			System.err.println("Warning: those districts don't exist: " + districtsNotFound);
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

	private static void loadSettings(ObjectMapper objectMapper, Districts districts, File settingsFile) throws IOException
	{
		final long settingsLastModifiedTemp = settingsFile.lastModified();
		if (settingsLastModifiedTemp > settingsLastModified)
		{
			System.err.println("Info: (re)loading settings from file \"" + settingsPath + "\".");
			settings = objectMapper.readValue(settingsFile, Settings.class);
			settingsLastModified = settingsLastModifiedTemp;
			districtsNotFound = settings.districtsOfInterest().parallelStream()
					.filter(Predicate.not(new HashSet<>(districts.getLanguage().values())::contains))
					.collect(Collectors.toSet());
			printDistrictsNotFoundWarning();
		} else if (settingsLastModifiedTemp == 0 && settingsLastModified != 0)
		{
			System.err.println("Warning: couldn't find \"" + settingsPath + "\", using default settings");
			settings = DEFAULT_SETTINGS;
			settingsLastModified = 0;
			districtsNotFound = Collections.emptySet();
		}
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

	private static record Districts(
			Map<String, String> HE,
			Map<String, String> EN,
			Map<String, String> AR,
			Map<String, String> RU
	)
	{
		private Map<String, String> getLanguage()
		{
			return switch (settings.language())
					{
						case HE -> HE();
						case EN -> EN();
						case AR -> AR();
						case RU -> RU();
					};
		}
	}
}