package info.lifeinuk.backend.acquisition;

import info.lifeinuk.backend.source.SourceEndpoint;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Road closures transport for the canonical endpoint only. The subscription key is sent solely as the
 * Ocp-Apim-Subscription-Key header and never appears in a URL, message or persisted value.
 */
@Component
class NationalHighwaysHttp {
    static final String USER_AGENT = "LifeInUK/0.0.1 (National Highways road closures acquisition)";
    private final JsonEvidenceHttp transport;
    private final NationalHighwaysRoadsProperties properties;

    @Autowired
    NationalHighwaysHttp(NationalHighwaysRoadsProperties properties) {
        this(new JsonEvidenceHttp(USER_AGENT), properties);
    }

    // Offline test seam only; there is no production destination override.
    NationalHighwaysHttp(HttpClient client, Duration timeout, NationalHighwaysRoadsProperties properties) {
        this(new JsonEvidenceHttp(client, timeout, USER_AGENT), properties);
    }

    private NationalHighwaysHttp(JsonEvidenceHttp transport, NationalHighwaysRoadsProperties properties) {
        this.transport = transport;
        this.properties = properties;
    }

    /** Fails before any run or request when no header-safe key is configured; the key is never echoed. */
    void requireCredential() {
        String key = properties.apiKey();
        if (key == null || key.isEmpty() || key.length() > 256
                || !key.chars().allMatch(c -> c >= 0x21 && c <= 0x7e)) {
            throw new IllegalStateException("A usable National Highways subscription key is not configured");
        }
    }

    JsonEvidenceHttp.Response receive(SourceEndpoint endpoint, String query) {
        if (!SourceEndpoint.NATIONAL_HIGHWAYS_ROAD_CLOSURES_KEY.equals(endpoint.getKey())
                || !SourceEndpoint.NATIONAL_HIGHWAYS_ROAD_CLOSURES_URL.equals(endpoint.getUrl())
                || !properties.closuresUrl().equals(endpoint.getUrl())) {
            throw new IllegalArgumentException("National Highways transport requires its canonical road closures endpoint");
        }
        requireCredential();
        return transport.receive(endpoint, query, Map.of(
                NationalHighwaysRoadsProperties.SUBSCRIPTION_KEY_HEADER, properties.apiKey(),
                NationalHighwaysRoadsProperties.RESPONSE_MEDIA_TYPE_HEADER, NationalHighwaysRoadsProperties.RESPONSE_MEDIA_TYPE,
                NationalHighwaysRoadsProperties.DATA_FORMAT_HEADER, NationalHighwaysRoadsProperties.DATA_FORMAT));
    }
}
