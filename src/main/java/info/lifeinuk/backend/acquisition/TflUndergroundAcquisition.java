package info.lifeinuk.backend.acquisition;

import info.lifeinuk.backend.evidence.AcquisitionHistory;
import info.lifeinuk.backend.source.QualifiedSourceEndpoints;
import info.lifeinuk.backend.source.SourceEndpoint;
import info.lifeinuk.backend.underground.UndergroundEvidenceProjection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** One explicit internal invocation = one attempt. No scheduling, retry or URL input. */
@Service
public class TflUndergroundAcquisition {
    private final QualifiedSourceEndpoints endpoints;
    private final AcquisitionHistory history;
    private final TflUndergroundHttp http;
    private final UndergroundEvidenceProjection projection;

    TflUndergroundAcquisition(QualifiedSourceEndpoints endpoints, AcquisitionHistory history, TflUndergroundHttp http,
            UndergroundEvidenceProjection projection) {
        this.endpoints = endpoints;
        this.history = history;
        this.http = http;
        this.projection = projection;
    }

    /**
     * The run's persisted status remains the acquisition outcome. Projection is attempted only for
     * committed evidence and is reported separately; it never changes the run or the evidence.
     */
    public record Result(UUID runId, Optional<UndergroundEvidenceProjection.Result> projection) { }

    @Transactional(propagation = Propagation.NEVER)
    public Result acquire() {
        SourceEndpoint endpoint = endpoints.requireTflUnderground();
        UUID runId = history.start(endpoint.getId());
        JsonEvidenceHttp.Response response;
        try {
            response = http.receive(endpoint);
        } catch (AcquisitionFailure failure) {
            history.fail(runId, failure.code(), failure.getMessage());
            return new Result(runId, Optional.empty());
        } catch (RuntimeException failure) {
            history.fail(runId, "TRANSPORT", "HTTP transport could not complete");
            return new Result(runId, Optional.empty());
        }
        UUID evidenceId;
        try {
            evidenceId = history.succeed(runId, response.bytes(), response.mediaType(), response.observedAt());
        } catch (RuntimeException persistenceFailure) {
            // Success transaction rolled back: retain STARTED, then record a bounded failure separately.
            // If the DB is unavailable or commit outcome is uncertain, fail() propagates rather than inventing success.
            history.fail(runId, "EVIDENCE_PERSISTENCE", "Evidence and successful completion could not be persisted");
            return new Result(runId, Optional.empty());
        }
        // succeed() committed its own transaction: projection reads the durable artifact, never response bytes.
        return new Result(runId, Optional.of(projection.projectEvidence(evidenceId)));
    }
}
