package info.lifeinuk.backend.marketindices;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Collector ingestion (token-protected by {@link MarketIndexIngestSecurity}) and the public latest-value read,
 * which is off unless MARKET_INDICES_PUBLIC_ENABLED is true. Every response is {@code Cache-Control: no-store};
 * errors are {@code {code, message}} as elsewhere in this API.
 */
@RestController
class MarketIndexController {
    private static final Logger log = LoggerFactory.getLogger(MarketIndexController.class);
    static final String OBSERVATIONS = "/api/internal/market-indices/observations";
    static final String HEARTBEAT = "/api/internal/market-indices/heartbeat";
    static final String LATEST = "/api/market-indices/latest";

    private final MarketIndexService service;

    MarketIndexController(MarketIndexService service) { this.service = service; }

    @PostMapping(OBSERVATIONS)
    ResponseEntity<?> observations(HttpServletRequest request) throws IOException {
        byte[] body = body(request);
        var batch = MarketIndexPayloads.batch(body, service.now(), service.properties());
        return ok(service.ingest(batch, credential(request)));
    }

    @PostMapping(HEARTBEAT)
    ResponseEntity<?> heartbeat(HttpServletRequest request) throws IOException {
        byte[] body = body(request);
        var heartbeat = MarketIndexPayloads.heartbeat(body, service.now(), service.properties());
        return ok(service.heartbeat(heartbeat, credential(request)));
    }

    @GetMapping(LATEST)
    ResponseEntity<?> latest() {
        if (!service.properties().publicEnabled()) {
            return error(HttpStatus.NOT_FOUND, "MARKET_INDICES_DISABLED", "Market index data is not available.");
        }
        return ok(service.latest());
    }

    /** JSON only, at most {@link MarketIndexPayloads#MAX_BODY_BYTES}, checked before and while reading. */
    private static byte[] body(HttpServletRequest request) throws IOException {
        String type = request.getContentType();
        MediaType mediaType;
        try {
            mediaType = type == null ? null : MediaType.parseMediaType(type);
        } catch (IllegalArgumentException invalid) {
            mediaType = null;
        }
        if (mediaType == null || !MediaType.APPLICATION_JSON.equalsTypeAndSubtype(mediaType)) {
            throw new MarketIndexPayloads.Rejected(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json.");
        }
        if (request.getContentLengthLong() > MarketIndexPayloads.MAX_BODY_BYTES) throw tooLarge();
        byte[] body = request.getInputStream().readNBytes(MarketIndexPayloads.MAX_BODY_BYTES + 1);
        if (body.length > MarketIndexPayloads.MAX_BODY_BYTES) throw tooLarge();
        return body;
    }

    private static MarketIndexPayloads.Rejected tooLarge() {
        return new MarketIndexPayloads.Rejected(HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                "The body must be at most " + MarketIndexPayloads.MAX_BODY_BYTES + " bytes.");
    }

    private static String credential(HttpServletRequest request) {
        return (String) request.getAttribute(MarketIndexIngestSecurity.CREDENTIAL_ATTRIBUTE);
    }

    @ExceptionHandler(MarketIndexPayloads.Rejected.class)
    ResponseEntity<Error> rejected(MarketIndexPayloads.Rejected rejected) {
        return error(rejected.status, rejected.code, rejected.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Error> failed(Exception failure) {
        log.warn("Market index request failed ({})", failure.getClass().getSimpleName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "MARKET_INDICES_FAILED", "The request could not be completed; retry later.");
    }

    private static ResponseEntity<?> ok(Object body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static ResponseEntity<Error> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new Error(code, message));
    }

    record Error(String code, String message) { }
}
