package info.lifeinuk.backend.roads;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Explicit owner-only command line operation; normal startup does nothing. */
@Component
class RoadsRunReplay implements ApplicationRunner {
    static final String OPTION = "project-roads-run";
    private static final Logger log = LoggerFactory.getLogger(RoadsRunReplay.class);
    private final RoadsEvidenceProjection projection;
    RoadsRunReplay(RoadsEvidenceProjection projection) { this.projection = projection; }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) { return; }
        var values = args.getOptionValues(OPTION);
        if (values == null || values.size() != 1) { throw new IllegalArgumentException("Expected one Roads run UUID"); }
        UUID id = UUID.fromString(values.getFirst());
        if (!id.toString().equalsIgnoreCase(values.getFirst())) { throw new IllegalArgumentException("Expected canonical Roads run UUID"); }
        var outcome = projection.projectRun(id);
        if (outcome == RoadsCurrentStateProjector.Outcome.CONFLICT) {
            throw new IllegalStateException("Roads snapshot conflict");
        }
        log.info("Roads logical run {} projection outcome: {}", id, outcome);
    }
}
