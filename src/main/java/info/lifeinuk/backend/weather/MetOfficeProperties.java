package info.lifeinuk.backend.weather;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Met Office Weather DataHub (Global spot) access from MET_OFFICE_BASE_URL and MET_OFFICE_API_KEY. As with OS
 * Names, the base URL is not a destination override: anything but the canonical site-specific v0 base fails
 * startup, so configuration cannot send the key elsewhere. Without a key the weather endpoint reports
 * not-configured and makes no outbound request. The key is never persisted and is redacted from toString().
 */
@ConfigurationProperties("life-in-uk.weather.met-office")
public record MetOfficeProperties(@DefaultValue(MetOfficeProperties.CANONICAL_BASE_URL) String baseUrl,
        @DefaultValue("") String apiKey) {
    public static final String CANONICAL_BASE_URL = "https://data.hub.api.metoffice.gov.uk/sitespecific/v0";

    public MetOfficeProperties {
        String base = baseUrl != null && baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (!CANONICAL_BASE_URL.equals(base)) {
            throw new IllegalArgumentException("Met Office base URL must be the canonical site-specific v0 base URL");
        }
        baseUrl = base;
        apiKey = apiKey == null ? "" : apiKey;
    }

    /** Header-safe visible ASCII only; the value itself is never echoed. */
    public boolean hasUsableApiKey() {
        return !apiKey.isEmpty() && apiKey.length() <= 4096 && apiKey.chars().allMatch(c -> c >= 0x21 && c <= 0x7e);
    }

    @Override
    public String toString() {
        return "MetOfficeProperties[baseUrl=" + baseUrl + ", apiKey=" + (apiKey.isEmpty() ? "<absent>" : "<redacted>") + "]";
    }
}
