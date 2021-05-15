package il.co;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RedAlert
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final SimpleDateFormat SIMPLE_DATE_FORMAT = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);

	public static void main(String... args) throws IOException
	{
		final URL url = new URL("https://www.oref.org.il/WarningMessages/alert/alerts.json");
		long currLastModified = 0;
		System.out.println("Starting Red Alert listener...");
		while (true)
		{
			if (url.openConnection() instanceof HttpURLConnection httpURLConnection)
			{
				httpURLConnection.setRequestProperty("Referer", "https://www.oref.org.il/12481-he/Pakar.aspx");
				httpURLConnection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
				httpURLConnection.setUseCaches(false);

				if (httpURLConnection.getContentLength() > 0 && httpURLConnection.getLastModified() > currLastModified)
					try (InputStream inputStream = httpURLConnection.getInputStream())
					{
						System.out.println(new StringBuilder("Content Length: ").append(httpURLConnection.getContentLength()).append(" bytes").append(System.lineSeparator())
								.append("Last Modified Date: ").append(SIMPLE_DATE_FORMAT.parse(httpURLConnection.getHeaderField("last-modified"))).append(System.lineSeparator())
								.append("Current Date: ").append(new Date()).append(System.lineSeparator())
								.append("Response: ").append(MAPPER.readValue(inputStream, RedAlertResponse.class).data));
						currLastModified = httpURLConnection.getLastModified();
					} catch (ParseException e)
					{
						e.printStackTrace();
					}
			}
		}
	}

	public static final record RedAlertResponse(List<String> data, long id, String title)
	{
	}
}