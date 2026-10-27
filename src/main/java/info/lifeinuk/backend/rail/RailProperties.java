package info.lifeinuk.backend.rail;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("life-in-uk.rail")
public record RailProperties(@DefaultValue(DEFAULT_BASE) String baseUrl, @DefaultValue("") String apiKey) {
    public static final String DEFAULT_BASE = "https://api1.raildata.org.uk/1010-live-arrival-board-arr/LDBWS";
    public RailProperties {
        baseUrl = baseUrl == null ? DEFAULT_BASE : baseUrl.replaceAll("/+$", "");
        URI uri = URI.create(baseUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Rail base URL must be an HTTPS URL without credentials, query or fragment");
        }
        apiKey = apiKey == null ? "" : apiKey;
    }
    boolean configured() { return !apiKey.isEmpty() && apiKey.length() <= 256 && apiKey.chars().allMatch(c -> c >= 33 && c <= 126); }
    @Override public String toString() { return "RailProperties[apiKey=<redacted>]"; }
}
