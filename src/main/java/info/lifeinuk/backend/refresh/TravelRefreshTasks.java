package info.lifeinuk.backend.refresh;

import info.lifeinuk.backend.acquisition.NationalHighwaysRoadClosuresAcquisition;
import info.lifeinuk.backend.acquisition.TflUndergroundAcquisition;
import info.lifeinuk.backend.evidence.AcquisitionHistory;
import info.lifeinuk.backend.evidence.RunStatus;
import info.lifeinuk.backend.roads.RoadsCurrentStateProjector;
import info.lifeinuk.backend.roads.RoadsEvidenceProjection;
import info.lifeinuk.backend.underground.UndergroundCurrentStateProjector;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One refresh = the existing acquisition followed by the existing projection, in that order. No new
 * acquisition, parsing or projection logic. Qualification is still enforced inside each acquisition, so an
 * un-qualified or disabled endpoint stops refresh without a deploy. A failure never touches Current State.
 *
 * <p>Logs carry only the run id, outcome and failure code; never keys, URLs or provider bodies.
 */
@Component
class TravelRefreshTasks {
    private static final Logger log = LoggerFactory.getLogger(TravelRefreshTasks.class);
    private final TflUndergroundAcquisition underground;
    private final NationalHighwaysRoadClosuresAcquisition roads;
    private final RoadsEvidenceProjection roadsProjection;
    private final AcquisitionHistory history;

    TravelRefreshTasks(TflUndergroundAcquisition underground, NationalHighwaysRoadClosuresAcquisition roads,
            RoadsEvidenceProjection roadsProjection, AcquisitionHistory history) {
        this.underground = underground;
        this.roads = roads;
        this.roadsProjection = roadsProjection;
        this.history = history;
    }

    RefreshPolicy.Outcome underground() {
        TflUndergroundAcquisition.Result result;
        try {
            result = underground.acquire();
        } catch (RuntimeException notStarted) {
            // e.g. endpoint no longer qualified/enabled: no run was recorded.
            log.warn("Underground refresh did not start: {}", notStarted.getClass().getSimpleName());
            return RefreshPolicy.Outcome.FAILED;
        }
        if (result.projection().isEmpty()) {
            return failedRun("Underground", result.runId());
        }
        var projection = result.projection().get();
        if (projection.failure() != null) {
            log.warn("Underground refresh run {} acquired evidence but projection failed: {}", result.runId(),
                    projection.failure());
            return RefreshPolicy.Outcome.FAILED;
        }
        if (projection.outcome() == UndergroundCurrentStateProjector.Outcome.CONFLICT) {
            log.warn("Underground refresh run {} projection conflict", result.runId());
            return RefreshPolicy.Outcome.FAILED;
        }
        log.info("Underground refresh run {} succeeded: {}", result.runId(), projection.outcome());
        return RefreshPolicy.Outcome.SUCCESS;
    }

    RefreshPolicy.Outcome roads() {
        UUID runId;
        try {
            runId = roads.acquire();
        } catch (RuntimeException notStarted) {
            // e.g. no usable subscription key or endpoint no longer qualified: no run was recorded.
            log.warn("Roads refresh did not start: {}", notStarted.getClass().getSimpleName());
            return RefreshPolicy.Outcome.FAILED;
        }
        var outcome = history.outcome(runId);
        if (outcome.status() != RunStatus.SUCCESS) {
            return failed("Roads", runId, outcome);
        }
        RoadsCurrentStateProjector.Outcome projected;
        try {
            projected = roadsProjection.projectRun(runId);
        } catch (RuntimeException projectionFailure) {
            log.warn("Roads refresh run {} acquired evidence but projection failed: {}", runId,
                    projectionFailure.getClass().getSimpleName());
            return RefreshPolicy.Outcome.FAILED;
        }
        if (projected == RoadsCurrentStateProjector.Outcome.CONFLICT) {
            log.warn("Roads refresh run {} projection conflict", runId);
            return RefreshPolicy.Outcome.FAILED;
        }
        log.info("Roads refresh run {} succeeded: {}", runId, projected);
        return RefreshPolicy.Outcome.SUCCESS;
    }

    private RefreshPolicy.Outcome failedRun(String name, UUID runId) {
        return failed(name, runId, history.outcome(runId));
    }

    private static RefreshPolicy.Outcome failed(String name, UUID runId, AcquisitionHistory.RunOutcome outcome) {
        boolean rateLimited = isRateLimited(outcome);
        log.warn("{} refresh run {} failed: {}{}", name, runId, outcome.failureCode(), rateLimited ? " (HTTP 429)" : "");
        return rateLimited ? RefreshPolicy.Outcome.RATE_LIMITED : RefreshPolicy.Outcome.FAILED;
    }

    /** The shared HTTP boundary records a non-200 status as HTTP_STATUS "... received &lt;code&gt;". */
    static boolean isRateLimited(AcquisitionHistory.RunOutcome outcome) {
        return outcome.status() == RunStatus.FAILED && "HTTP_STATUS".equals(outcome.failureCode())
                && outcome.failureMessage() != null && outcome.failureMessage().endsWith("received 429");
    }
}
