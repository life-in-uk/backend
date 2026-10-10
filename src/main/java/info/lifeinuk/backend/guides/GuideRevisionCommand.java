package info.lifeinuk.backend.guides;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Literal CLI options only; properties, profiles and the environment never invoke these operations.
 * {@code --guide-revision-create} stores an immutable DRAFT revision; {@code --guide-check} is read-only.
 * Neither publishes, and neither writes the public guide projection.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class GuideRevisionCommand implements ApplicationRunner {
    static final String CREATE = "guide-revision-create";
    static final String CHECK = "guide-check";
    static final String BASED_ON = "based-on";
    static final String AI_ASSISTED = "ai-assisted";
    static final String NOTE = "note";
    private static final Logger log = LoggerFactory.getLogger(GuideRevisionCommand.class);

    private final GuideImportReader reader;
    private final GuideRevisionService revisions;
    private final GuideQualityGate gate;
    private final String operator;

    GuideRevisionCommand(GuideImportReader reader, GuideRevisionService revisions, GuideQualityGate gate,
            @Value("${LIFE_IN_UK_OPERATOR:}") String operator) {
        this.reader = reader;
        this.revisions = revisions;
        this.gate = gate;
        this.operator = operator;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        boolean create = args.containsOption(CREATE);
        boolean check = args.containsOption(CHECK);
        boolean modifiers = args.containsOption(BASED_ON) || args.containsOption(AI_ASSISTED) || args.containsOption(NOTE);
        if (!create && !check) {
            if (modifiers) { throw new IllegalArgumentException("--based-on, --ai-assisted and --note require --" + CREATE); }
            return;
        }
        // Runs before other runners, so a conflicting combination fails before anything else executes.
        if (create && check || args.containsOption(GuideImportCommand.OPTION)) {
            throw new IllegalArgumentException("Run exactly one of --" + CREATE + ", --" + CHECK + " or --"
                    + GuideImportCommand.OPTION + " per command");
        }
        if (check) {
            if (modifiers) { throw new IllegalArgumentException("--" + CHECK + " takes no other options"); }
            var report = gate.check(uuid(single(args, CHECK)), Instant.now());
            log.info("\n{}", GuideQualityGate.render(report));
            return;
        }
        // Local read and complete validation finish before the revision transaction opens.
        var draft = reader.read(Path.of(single(args, CREATE)));
        UUID basedOn = args.containsOption(BASED_ON) ? uuid(single(args, BASED_ON)) : null;
        boolean aiAssisted = flag(args, AI_ASSISTED);
        String note = args.containsOption(NOTE) ? single(args, NOTE) : null;
        var result = revisions.create(draft, basedOn, operator, aiAssisted, note);
        var revision = result.revision();
        log.info("Guide {} revision {} {} #{} digest {}", revision.slug(), result.outcome(), revision.id(),
                revision.revisionNumber(), revision.draftDigest());
    }

    private static String single(ApplicationArguments args, String option) {
        List<String> values = args.getOptionValues(option);
        if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
            throw new IllegalArgumentException("--" + option + " requires exactly one value");
        }
        return values.getFirst();
    }

    private static boolean flag(ApplicationArguments args, String option) {
        if (!args.containsOption(option)) { return false; }
        var values = args.getOptionValues(option);
        if (values != null && !values.isEmpty()) {
            throw new IllegalArgumentException("--" + option + " is a flag and takes no value");
        }
        return true;
    }

    private static UUID uuid(String value) {
        UUID id;
        try { id = UUID.fromString(value); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Expected a revision UUID: " + value); }
        if (!id.toString().equalsIgnoreCase(value)) { throw new IllegalArgumentException("Expected a canonical revision UUID: " + value); }
        return id;
    }
}
