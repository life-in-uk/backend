package info.lifeinuk.backend.underground;

import info.lifeinuk.backend.evidence.UndergroundEvidence;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persisted evidence identity → provenance-checked lookup → accepted parser → accepted projector.
 * Never contacts a provider; Current State decisions remain entirely the projector's.
 */
@Service
public class UndergroundEvidenceProjection {
    private static final Logger log = LoggerFactory.getLogger(UndergroundEvidenceProjection.class);
    private final UndergroundEvidence evidence;
    private final TflUndergroundStatusParser parser;
    private final UndergroundCurrentStateProjector projector;

    UndergroundEvidenceProjection(UndergroundEvidence evidence, TflUndergroundStatusParser parser,
            UndergroundCurrentStateProjector projector) {
        this.evidence = evidence;
        this.parser = parser;
        this.projector = projector;
    }

    /** Lookup commits before parsing; the projector then owns its own locked transaction. */
    @Transactional(propagation = Propagation.NEVER)
    public Result projectEvidence(UUID evidenceArtifactId) {
        Objects.requireNonNull(evidenceArtifactId, "Evidence identity is required");
        UndergroundStatusInterpretation interpretation;
        try {
            var artifact = evidence.successful(evidenceArtifactId);
            if (artifact.isEmpty()) {
                return Result.failed(Result.Failure.EVIDENCE_NOT_ELIGIBLE);
            }
            try {
                interpretation = parser.parse(artifact.get());
            } catch (IllegalArgumentException invalid) {
                return Result.failed(Result.Failure.EVIDENCE_INVALID);
            }
            return Result.projected(projector.project(interpretation));
        } catch (RuntimeException failure) {
            // The projector's transaction rolled back; the previous snapshot and all evidence remain.
            log.warn("Underground projection of evidence {} failed", evidenceArtifactId, failure);
            return Result.failed(Result.Failure.PROJECTION_FAILED);
        }
    }

    /** Exactly one of the projector's own outcome or a bounded pre-/non-projection failure. */
    public record Result(UndergroundCurrentStateProjector.Outcome outcome, Failure failure) {
        public enum Failure { EVIDENCE_NOT_ELIGIBLE, EVIDENCE_INVALID, PROJECTION_FAILED }

        public Result {
            if ((outcome == null) == (failure == null)) {
                throw new IllegalArgumentException("Exactly one of outcome or failure is required");
            }
        }

        static Result projected(UndergroundCurrentStateProjector.Outcome outcome) { return new Result(outcome, null); }
        static Result failed(Failure failure) { return new Result(null, failure); }
    }
}
