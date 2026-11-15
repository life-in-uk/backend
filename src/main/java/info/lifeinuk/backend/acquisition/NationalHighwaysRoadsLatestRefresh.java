package info.lifeinuk.backend.acquisition;

import info.lifeinuk.backend.evidence.CapturedResponse;
import info.lifeinuk.backend.roads.NationalHighwaysRoadClosuresParser;
import info.lifeinuk.backend.roads.RoadsCurrentState;
import info.lifeinuk.backend.roads.RoadsCurrentStateProjector;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Latest-only Roads refresh used by the scheduler. It runs the same paced, paginated National Highways
 * fetch as {@link NationalHighwaysRoadClosuresAcquisition} (same lock, pacer and 8-page bound), then the
 * accepted parser, then the existing atomic {@link RoadsCurrentStateProjector}. It writes no ingestion run and
 * no evidence artifact, so repeated refreshes grow no table; only the single {@code roads_current_snapshot}
 * row is replaced. Every page must parse before anything is replaced, and any failure leaves the previous
 * snapshot and its time untouched to expire naturally.
 */
@Service
public class NationalHighwaysRoadsLatestRefresh {
    private final NationalHighwaysRoadClosuresAcquisition acquisition;
    private final NationalHighwaysRoadClosuresParser parser;
    private final RoadsCurrentStateProjector projector;

    NationalHighwaysRoadsLatestRefresh(NationalHighwaysRoadClosuresAcquisition acquisition,
            NationalHighwaysRoadClosuresParser parser, RoadsCurrentStateProjector projector) {
        this.acquisition = acquisition;
        this.parser = parser;
        this.projector = projector;
    }

    /** Exactly one of the projector's outcome or a bounded failure code and message (never provider text). */
    public record Result(RoadsCurrentStateProjector.Outcome outcome, String failureCode, String failureMessage) {
        public Result {
            if ((outcome == null) == (failureCode == null)) {
                throw new IllegalArgumentException("Exactly one of outcome or failure is required");
            }
        }

        static Result projected(RoadsCurrentStateProjector.Outcome outcome) { return new Result(outcome, null, null); }
        static Result failed(String code, String message) { return new Result(null, code, message); }

        /** The shared HTTP boundary reports a non-200 status as HTTP_STATUS "... received &lt;code&gt;". */
        public boolean rateLimited() {
            return "HTTP_STATUS".equals(failureCode) && failureMessage != null && failureMessage.endsWith("received 429");
        }
    }

    /** Throws only when the endpoint is not qualified/enabled or no usable key is configured (no request made). */
    @Transactional(propagation = Propagation.NEVER)
    public Result refresh() {
        var fetched = acquisition.fetchLatest();
        if (fetched instanceof NationalHighwaysRoadClosuresAcquisition.Failure failure) {
            return Result.failed(failure.code(), failure.message());
        }
        var pages = (NationalHighwaysRoadClosuresAcquisition.Pages) fetched;
        UUID observationId = UUID.randomUUID();
        var records = new ArrayList<RoadsCurrentState.Record>();
        try {
            for (CapturedResponse page : pages.responses()) {
                var interpretation = parser.parse(UUID.randomUUID(), observationId, page.observedAt(),
                        page.requestedAt(), page.pageNumber(), page.requestQuery(), page.payload());
                for (var situation : interpretation.situations()) {
                    for (var closure : situation.records()) {
                        records.add(new RoadsCurrentState.Record(situation.id(), situation.versionTime(),
                                situation.confidentiality(), situation.informationStatus(), closure));
                    }
                }
            }
        } catch (IllegalArgumentException invalid) {
            return Result.failed("INVALID_RESPONSE", "National Highways response did not match the accepted closures format");
        }
        try {
            return Result.projected(projector.project(new RoadsCurrentState(observationId, pages.endpoint().getId(),
                    pages.acquiredAt(), Instant.now(), pages.responses().size(), records)));
        } catch (RuntimeException failure) {
            // The projector's own transaction rolled back; the previous snapshot remains.
            return Result.failed("PROJECTION_FAILED", "Current State could not be replaced");
        }
    }
}
