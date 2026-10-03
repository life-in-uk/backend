package info.lifeinuk.backend.acquisition;

import info.lifeinuk.backend.source.SourceEndpoint;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Anonymous GET for the canonical Underground status endpoint only. */
@Component
class TflUndergroundHttp {
    static final int MAX_BODY_BYTES = JsonEvidenceHttp.MAX_BODY_BYTES;
    static final Duration RESPONSE_TIMEOUT = JsonEvidenceHttp.RESPONSE_TIMEOUT;
    static final String USER_AGENT = "LifeInUK/0.0.1 (TfL Underground acquisition)";
    private final JsonEvidenceHttp transport;

    TflUndergroundHttp() {
        transport = new JsonEvidenceHttp(USER_AGENT);
    }

    // Offline test seam only; there is no production destination override.
    TflUndergroundHttp(HttpClient client, Duration timeout) {
        transport = new JsonEvidenceHttp(client, timeout, USER_AGENT);
    }

    JsonEvidenceHttp.Response receive(SourceEndpoint endpoint) {
        if (!SourceEndpoint.TFL_UNDERGROUND_KEY.equals(endpoint.getKey())
                || !SourceEndpoint.TFL_UNDERGROUND_URL.equals(endpoint.getUrl())) {
            throw new IllegalArgumentException("TfL transport requires its canonical Underground endpoint");
        }
        return transport.receive(endpoint);
    }
}
