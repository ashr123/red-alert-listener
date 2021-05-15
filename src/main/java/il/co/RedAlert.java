package il.co;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
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

	public static void main(String... args) throws MalformedURLException
	{
		final URL url = new URL("https://www.oref.org.il/WarningMessages/alert/alerts.json");
		long currLastModified = 0;
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
							System.out.println(new StringBuilder("Content Length: ").append(httpURLConnection.getContentLength()).append(" bytes").append(System.lineSeparator())
									.append("Last Modified Date: ").append(lastModified).append(System.lineSeparator())
									.append("Current Date: ").append(new Date()).append(System.lineSeparator())
									.append("Response: ").append(MAPPER.readValue(inputStream, RedAlertResponse.class).data));
						}
				} else
					System.out.println("Not a HTTP connection!");
			} catch (IOException | ParseException e)
			{
				e.printStackTrace();
			}
	}

	public static final record RedAlertResponse(List<String> data, long id, String title)
	{
	}
}