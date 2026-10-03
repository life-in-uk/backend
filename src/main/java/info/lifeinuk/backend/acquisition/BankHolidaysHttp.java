package info.lifeinuk.backend.acquisition;

import info.lifeinuk.backend.source.SourceEndpoint;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Transport for the approved endpoint only; no externally callable URL API. */
@Component
class BankHolidaysHttp {
    static final int MAX_BODY_BYTES = JsonEvidenceHttp.MAX_BODY_BYTES;
    static final Duration RESPONSE_TIMEOUT = JsonEvidenceHttp.RESPONSE_TIMEOUT;
    static final String USER_AGENT = "LifeInUK/0.0.1 (Bank Holidays acquisition)";
    private final JsonEvidenceHttp transport;

    BankHolidaysHttp() {
        transport = new JsonEvidenceHttp(USER_AGENT);
    }

    // Package-private seam for offline transport tests; no runtime target override exists.
    BankHolidaysHttp(HttpClient client, Duration timeout) {
        transport = new JsonEvidenceHttp(client, timeout, USER_AGENT);
    }

    Response receive(SourceEndpoint endpoint) {
        if (!SourceEndpoint.BANK_HOLIDAYS_KEY.equals(endpoint.getKey())
                || !SourceEndpoint.BANK_HOLIDAYS_URL.equals(endpoint.getUrl())) {
            throw new IllegalArgumentException("Bank Holidays transport requires its canonical endpoint");
        }
        var response = transport.receive(endpoint);
        return new Response(response.bytes(), response.mediaType(), response.observedAt());
    }

    record Response(byte[] bytes, String mediaType, Instant observedAt) { }
}
