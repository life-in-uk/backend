package info.lifeinuk.backend.acquisition;

import info.lifeinuk.backend.evidence.AcquisitionHistory;
import info.lifeinuk.backend.source.QualifiedSourceEndpoints;
import info.lifeinuk.backend.source.SourceEndpoint;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** One explicit internal invocation = one attempt. No scheduling, retry or URL input. */
@Service
public class BankHolidaysAcquisition {
    private final QualifiedSourceEndpoints endpoints;
    private final AcquisitionHistory history;
    private final BankHolidaysHttp http;

    BankHolidaysAcquisition(QualifiedSourceEndpoints endpoints, AcquisitionHistory history, BankHolidaysHttp http) {
        this.endpoints = endpoints;
        this.history = history;
        this.http = http;
    }

    @Transactional(propagation = Propagation.NEVER)
    public UUID acquire() {
        SourceEndpoint endpoint = endpoints.requireBankHolidays();
        UUID runId = history.start(endpoint.getId());
        BankHolidaysHttp.Response response;
        try {
            response = http.receive(endpoint);
        } catch (AcquisitionFailure failure) {
            history.fail(runId, failure.code(), failure.getMessage());
            return runId;
        } catch (RuntimeException failure) {
            history.fail(runId, "TRANSPORT", "HTTP transport could not complete");
            return runId;
        }
        try {
            history.succeed(runId, response.bytes(), response.mediaType(), response.observedAt());
        } catch (RuntimeException persistenceFailure) {
            // Success transaction rolled back: retain STARTED, then record a bounded failure separately.
            // If the DB is unavailable or commit outcome is uncertain, fail() propagates rather than inventing success.
            history.fail(runId, "EVIDENCE_PERSISTENCE", "Evidence and successful completion could not be persisted");
        }
        return runId;
    }
}
