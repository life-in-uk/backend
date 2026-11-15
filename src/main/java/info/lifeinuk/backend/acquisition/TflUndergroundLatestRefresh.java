package info.lifeinuk.backend.acquisition;

import info.lifeinuk.backend.source.QualifiedSourceEndpoints;
import info.lifeinuk.backend.source.SourceEndpoint;
import info.lifeinuk.backend.underground.TflUndergroundStatusParser;
import info.lifeinuk.backend.underground.UndergroundCurrentStateProjector;
import info.lifeinuk.backend.underground.UndergroundStatusInterpretation;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Latest-only Underground refresh used by the scheduler: one request to the qualified TfL endpoint, the
 * accepted parser, then the existing atomic Current State replacement. Unlike {@link TflUndergroundAcquisition}
 * it writes no ingestion run and no evidence artifact, so repeated refreshes do not grow any table; only the
 * single Current State snapshot is replaced. Any failure leaves the previous snapshot, and its real observation
 * time, untouched so it can expire naturally.
 */
@Service
public class TflUndergroundLatestRefresh {
    private final QualifiedSourceEndpoints endpoints;
    private final TflUndergroundHttp http;
    private final TflUndergroundStatusParser parser;
    private final UndergroundCurrentStateProjector projector;

    TflUndergroundLatestRefresh(QualifiedSourceEndpoints endpoints, TflUndergroundHttp http,
            TflUndergroundStatusParser parser, UndergroundCurrentStateProjector projector) {
        this.endpoints = endpoints;
        this.http = http;
        this.parser = parser;
        this.projector = projector;
    }

    /** Exactly one of the projector's outcome or a bounded failure code and message (never provider text). */
    public record Result(UndergroundCurrentStateProjector.Outcome outcome, String failureCode, String failureMessage) {
        public Result {
            if ((outcome == null) == (failureCode == null)) {
                throw new IllegalArgumentException("Exactly one of outcome or failure is required");
            }
        }

        static Result projected(UndergroundCurrentStateProjector.Outcome outcome) { return new Result(outcome, null, null); }
        static Result failed(String code, String message) { return new Result(null, code, message); }

        /** The shared HTTP boundary reports a non-200 status as HTTP_STATUS "... received &lt;code&gt;". */
        public boolean rateLimited() {
            return "HTTP_STATUS".equals(failureCode) && failureMessage != null && failureMessage.endsWith("received 429");
        }
    }

    /** Throws only when the endpoint is not qualified/enabled; then no request is made. */
    @Transactional(propagation = Propagation.NEVER)
    public Result refresh() {
        SourceEndpoint endpoint = endpoints.requireTflUnderground();
        JsonEvidenceHttp.Response response;
        try {
            response = http.receive(endpoint);
        } catch (AcquisitionFailure failure) {
            return Result.failed(failure.code(), failure.getMessage());
        } catch (RuntimeException failure) {
            return Result.failed("TRANSPORT", "HTTP transport could not complete");
        }
        UndergroundStatusInterpretation observation;
        try {
            observation = parser.parse(UUID.randomUUID(), response.bytes(), response.observedAt());
        } catch (IllegalArgumentException invalid) {
            return Result.failed("INVALID_RESPONSE", "TfL response did not match the accepted Underground format");
        }
        if (observation.lines().isEmpty()) {
            // A valid but empty list is never treated as a complete status for the network.
            return Result.failed("INCOMPLETE_RESPONSE", "TfL response listed no Underground lines");
        }
        try {
            return Result.projected(projector.project(observation));
        } catch (RuntimeException failure) {
            // The projector's own transaction rolled back; the previous snapshot remains.
            return Result.failed("PROJECTION_FAILED", "Current State could not be replaced");
        }
    }
}
