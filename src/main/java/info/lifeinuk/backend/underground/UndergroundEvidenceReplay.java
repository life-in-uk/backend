package info.lifeinuk.backend.underground;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Explicit owner operation only: {@code --project-underground-evidence=<uuid>} on the command line projects
 * exactly one persisted artifact through the production projection. Without the option, startup does nothing.
 */
@Component
class UndergroundEvidenceReplay implements ApplicationRunner {
    static final String OPTION = "project-underground-evidence";
    private static final Logger log = LoggerFactory.getLogger(UndergroundEvidenceReplay.class);
    private final UndergroundEvidenceProjection projection;

    UndergroundEvidenceReplay(UndergroundEvidenceProjection projection) { this.projection = projection; }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) {
            return;
        }
        var values = args.getOptionValues(OPTION);
        if (values == null || values.size() != 1) {
            throw new IllegalArgumentException("--" + OPTION + " requires exactly one evidence UUID");
        }
        UUID id;
        try {
            id = UUID.fromString(values.getFirst());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("--" + OPTION + " requires a canonical evidence UUID");
        }
        if (!id.toString().equalsIgnoreCase(values.getFirst())) {
            throw new IllegalArgumentException("--" + OPTION + " requires a canonical evidence UUID");
        }
        var result = projection.projectEvidence(id);
        if (result.failure() != null) {
            throw new IllegalStateException("Underground evidence " + id + " was not projected: " + result.failure());
        }
        log.info("Underground evidence {} projection outcome: {}", id, result.outcome());
    }
}
