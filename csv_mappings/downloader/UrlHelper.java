package csv_mappings.downloader;

import java.net.MalformedURLException;
import java.net.URL;

public final class UrlHelper {
    private UrlHelper() { }
    
    public static URL createValidUrl(final String urlString) throws IllegalArgumentException {
        try {
            return new URL(urlString);
        }
        catch (final MalformedURLException malformedURLException) {
            throw new IllegalArgumentException("Unexpected malformed URL", malformedURLException);
        }
    }
}
