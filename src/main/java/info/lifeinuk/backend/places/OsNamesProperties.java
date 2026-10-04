package info.lifeinuk.backend.places;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * OS Names access configuration from OS_NAMES_BASE_URL and OS_NAMES_API_KEY. The base URL is not a destination
 * override: anything but the canonical v1 base fails startup, so configuration cannot redirect the key. The key
 * is optional at startup (search then reports not-configured), never persisted, and redacted from toString().
 */
@ConfigurationProperties("life-in-uk.places.os-names")
public record OsNamesProperties(@DefaultValue(OsNamesProperties.CANONICAL_BASE_URL) String baseUrl,
        @DefaultValue("") String apiKey) {
    public static final String CANONICAL_BASE_URL = "https://api.os.uk/search/names/v1";

    public OsNamesProperties {
        String base = baseUrl != null && baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (!CANONICAL_BASE_URL.equals(base)) {
            throw new IllegalArgumentException("OS Names base URL must be the canonical v1 base URL");
        }
        baseUrl = base;
        apiKey = apiKey == null ? "" : apiKey;
    }

    /** Header-safe visible ASCII only; the value itself is never echoed. */
    public boolean hasUsableApiKey() {
        return !apiKey.isEmpty() && apiKey.length() <= 256 && apiKey.chars().allMatch(c -> c >= 0x21 && c <= 0x7e);
    }

    @Override
    public String toString() {
        return "OsNamesProperties[baseUrl=" + baseUrl + ", apiKey=" + (apiKey.isEmpty() ? "<absent>" : "<redacted>") + "]";
    }
}
