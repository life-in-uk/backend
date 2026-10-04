package info.lifeinuk.backend.roads;

import info.lifeinuk.backend.evidence.NationalHighwaysRunEvidence;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Owner-selected successful run → all pages → #28 interpretation → atomic Current State. No network. */
@Service
public class RoadsEvidenceProjection {
    private final NationalHighwaysRunEvidence evidence;
    private final NationalHighwaysRoadClosuresParser parser;
    private final RoadsCurrentStateProjector projector;

    RoadsEvidenceProjection(NationalHighwaysRunEvidence evidence, NationalHighwaysRoadClosuresParser parser,
            RoadsCurrentStateProjector projector) {
        this.evidence = evidence; this.parser = parser; this.projector = projector;
    }

    @Transactional(propagation = Propagation.NEVER)
    public RoadsCurrentStateProjector.Outcome projectRun(UUID id) {
        var run = evidence.requireSuccessful(id);
        var records = new ArrayList<RoadsCurrentState.Record>();
        for (var page : run.pages()) {
            var interpretation = parser.parse(page);
            for (var situation : interpretation.situations()) {
                for (var closure : situation.records()) {
                    records.add(new RoadsCurrentState.Record(situation.id(), situation.versionTime(),
                            situation.confidentiality(), situation.informationStatus(), closure));
                }
            }
        }
        return projector.project(new RoadsCurrentState(run.id(), run.endpointId(), run.startedAt(),
                Instant.now(), run.pages().size(), records));
    }
}
