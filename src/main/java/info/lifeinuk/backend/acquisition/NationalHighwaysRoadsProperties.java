package info.lifeinuk.backend.acquisition;

import info.lifeinuk.backend.source.SourceEndpoint;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * External National Highways Roads access configuration, bound from NATIONAL_HIGHWAYS_ROADS_BASE_URL and
 * NATIONAL_HIGHWAYS_API_KEY. The base URL is not a destination override: it must be the canonical v2.0
 * production base, so configuration can never redirect the subscription key. The key is optional (no
 * acquisition exists yet), is never persisted, and is redacted from {@link #toString()}.
 */
@ConfigurationProperties("life-in-uk.acquisition.national-highways")
public record NationalHighwaysRoadsProperties(
        @DefaultValue(SourceEndpoint.NATIONAL_HIGHWAYS_ROADS_BASE_URL) String roadsBaseUrl,
        @DefaultValue("") String apiKey) {
    /** Official contract headers for the later acquisition issue; no request is made by this foundation. */
    public static final String SUBSCRIPTION_KEY_HEADER = "Ocp-Apim-Subscription-Key";
    public static final String RESPONSE_MEDIA_TYPE_HEADER = "X-Response-MediaType";
    public static final String RESPONSE_MEDIA_TYPE = "application/json";
    public static final String DATA_FORMAT_HEADER = "X-Data-Format";
    public static final String DATA_FORMAT = "DATEXII";

    public NationalHighwaysRoadsProperties {
        String base = roadsBaseUrl != null && roadsBaseUrl.endsWith("/")
                ? roadsBaseUrl.substring(0, roadsBaseUrl.length() - 1) : roadsBaseUrl;
        if (!SourceEndpoint.NATIONAL_HIGHWAYS_ROADS_BASE_URL.equals(base)) {
            throw new IllegalArgumentException("National Highways roads base URL must be the canonical v2.0 production base URL");
        }
        roadsBaseUrl = base;
        apiKey = apiKey == null ? "" : apiKey;
    }

    public boolean hasApiKey() { return !apiKey.isBlank(); }

    /** Equal to the canonical persisted endpoint URL by construction. */
    public String closuresUrl() { return roadsBaseUrl + "/closures"; }

    @Override
    public String toString() {
        return "NationalHighwaysRoadsProperties[roadsBaseUrl=" + roadsBaseUrl
                + ", apiKey=" + (hasApiKey() ? "<redacted>" : "<absent>") + "]";
    }
}
