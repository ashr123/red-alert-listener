package il.co;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sound.sampled.*;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

public class RedAlert
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final SimpleDateFormat SIMPLE_DATE_FORMAT = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
	private static final File FILE = new File("red-alert-settings.json");

	public static void main(String... args) throws IOException, UnsupportedAudioFileException, LineUnavailableException
	{
		final URL url = new URL("https://www.oref.org.il/WarningMessages/alert/alerts.json");
		long currLastModified = 0;

		try (Clip clip = AudioSystem.getClip();
		     AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(new BufferedInputStream(Objects.requireNonNull(RedAlert.class.getResourceAsStream("/alarmSound.wav")))))
		{
			clip.open(audioInputStream);
			System.out.println("Starting Red Alert listener...");
			while (true)
				try
				{
					if (url.openConnection() instanceof HttpURLConnection httpURLConnection)
					{
						httpURLConnection.setRequestProperty("Referer", "https://www.oref.org.il/12481-he/Pakar.aspx");
						httpURLConnection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
						httpURLConnection.setConnectTimeout(5000);
						httpURLConnection.setReadTimeout(5000);
						httpURLConnection.setUseCaches(false);

						Date lastModified;
						if (httpURLConnection.getContentLength() > 0 && (lastModified = SIMPLE_DATE_FORMAT.parse(httpURLConnection.getHeaderField("last-modified"))).getTime() > currLastModified)
							try (InputStream inputStream = httpURLConnection.getInputStream())
							{
								currLastModified = httpURLConnection.getLastModified();
								final List<String> data = MAPPER.readValue(inputStream, RedAlertResponse.class).data();
								System.out.println(new StringBuilder("Content Length: ").append(httpURLConnection.getContentLength()).append(" bytes").append(System.lineSeparator())
										.append("Last Modified Date: ").append(lastModified).append(System.lineSeparator())
										.append("Current Date: ").append(new Date()).append(System.lineSeparator())
										.append("Response: ").append(data));

								if (FILE.exists())
								{
									final Settings settings = MAPPER.readValue(FILE, Settings.class);
									final List<String> importantAreas = data.parallelStream()
											.filter(settings.areasOfInterest()::contains)
											.collect(Collectors.toList());
									if (settings.isMakeSound() && (settings.isAlertAll() || !importantAreas.isEmpty()))
									{
										clip.setFramePosition(0);
										clip.loop(settings.soundLoopCount());
									}
									if (!importantAreas.isEmpty())
										System.out.println("ALERT: " + importantAreas);
								} else
									System.out.println("Warning: File doesn't exists");
							}
					} else
						System.out.println("Error: Not a HTTP connection!");
				} catch (IOException | ParseException e)
				{
					e.printStackTrace();
				}
		}
	}

	public static final record RedAlertResponse(List<String> data, long id, String title)
	{
	}

	public static final record Settings(boolean isMakeSound, boolean isAlertAll, int soundLoopCount,
	                                    List<String> areasOfInterest)
	{
	}
}