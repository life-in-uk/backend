package info.lifeinuk.backend.guides;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Explicit Guide workflow operations. Literal CLI options only; properties, profiles and the environment never invoke
 * them, and no scheduled path exists. {@code --guide-revision-create} stores an immutable DRAFT revision and
 * {@code --guide-check} is read-only. Baseline and activation are explicit administrative commands. Publication and
 * withdrawal refuse until the workflow is activated. At most one Guide operation, including {@code --import-guide},
 * may be requested per run.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class GuideWorkflowCommand implements ApplicationRunner {
    static final String BASELINE = "guide-baseline";
    static final String ACTIVATE = "guide-activate";
    static final String VERIFY = "guide-verify";
    static final String CREATE = "guide-revision-create";
    static final String CHECK = "guide-check";
    static final String PUBLISH = "guide-publish";
    static final String WITHDRAW = "guide-withdraw";
    static final String BASED_ON = "based-on";
    static final String AI_ASSISTED = "ai-assisted";
    static final String NOTE = "note";
    static final String EXPECT_DIGEST = "expect-digest";
    static final String EXPECT_CURRENT = "expect-current";
    static final String CHECKLIST = "checklist";
    static final String VERIFIED_ON = "verified-on";
    static final String CONFIRM = "confirm";
    static final String REASON = "reason";
    /** The options each operation accepts besides its own. */
    private static final Map<String, Set<String>> MODIFIERS = Map.of(
            BASELINE, Set.of(), ACTIVATE, Set.of(CONFIRM), VERIFY, Set.of(),
            CREATE, Set.of(BASED_ON, AI_ASSISTED, NOTE),
            CHECK, Set.of(),
            PUBLISH, Set.of(EXPECT_DIGEST, EXPECT_CURRENT, CHECKLIST, VERIFIED_ON, CONFIRM),
            WITHDRAW, Set.of(EXPECT_CURRENT, REASON));
    private static final Set<String> ALL_MODIFIERS = MODIFIERS.values().stream().flatMap(Set::stream).collect(Collectors.toSet());
    private static final Logger log = LoggerFactory.getLogger(GuideWorkflowCommand.class);

    private final GuideImportReader reader;
    private final GuideRevisionService revisions;
    private final GuideQualityGate gate;
    private final GuidePublicationService publications;
    private final GuideActivation activation;
    private final String operator;

    GuideWorkflowCommand(GuideImportReader reader, GuideRevisionService revisions, GuideQualityGate gate,
            GuidePublicationService publications, GuideActivation activation,
            @Value("${LIFE_IN_UK_OPERATOR:}") String operator) {
        this.reader = reader;
        this.revisions = revisions;
        this.gate = gate;
        this.publications = publications;
        this.activation = activation;
        this.operator = operator;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        var operations = MODIFIERS.keySet().stream().filter(args::containsOption).toList();
        var modifiers = ALL_MODIFIERS.stream().filter(args::containsOption).collect(Collectors.toSet());
        if (operations.isEmpty()) {
            if (!modifiers.isEmpty()) { throw new IllegalArgumentException("Options " + modifiers + " require a Guide operation"); }
            return;
        }
        // Runs before other runners, so a conflicting combination fails before anything else executes.
        if (operations.size() > 1 || args.containsOption(GuideImportCommand.OPTION)) {
            throw new IllegalArgumentException("Run exactly one Guide operation per command");
        }
        String operation = operations.getFirst();
        var unexpected = modifiers.stream().filter(option -> !MODIFIERS.get(operation).contains(option)).sorted().toList();
        if (!unexpected.isEmpty()) { throw new IllegalArgumentException("--" + operation + " does not accept " + unexpected); }
        switch (operation) {
            case BASELINE -> baseline(args);
            case ACTIVATE -> activate(args);
            case VERIFY -> verify(args);
            case CREATE -> create(args);
            case CHECK -> log.info("\n{}", GuideQualityGate.render(gate.check(uuid(single(args, CHECK)), Instant.now())));
            case PUBLISH -> publish(args);
            case WITHDRAW -> withdraw(args);
            default -> throw new IllegalStateException(operation);
        }
    }

    private void baseline(ApplicationArguments args) throws Exception {
        var artifact = reader.read(Path.of(single(args, BASELINE)));
        log.info("Guide legacy baseline {}", activation.baseline(artifact, operator));
    }

    private void activate(ApplicationArguments args) {
        if (!flag(args, ACTIVATE)) throw new IllegalArgumentException("--guide-activate is a flag");
        var result = activation.activate(operator, flag(args, CONFIRM));
        log.info("Guide workflow activation {} publications {}", result.activated(), result.publications());
    }

    private void verify(ApplicationArguments args) {
        flag(args, VERIFY);
        var result = activation.verify();
        log.info("Guide workflow integrity activated {} publications {}", result.activated(), result.publications());
    }

    private void create(ApplicationArguments args) throws Exception {
        // Local read and complete validation finish before the revision transaction opens.
        var draft = reader.read(Path.of(single(args, CREATE)));
        UUID basedOn = args.containsOption(BASED_ON) ? uuid(single(args, BASED_ON)) : null;
        String note = args.containsOption(NOTE) ? single(args, NOTE) : null;
        var result = revisions.create(draft, basedOn, operator, flag(args, AI_ASSISTED), note);
        var revision = result.revision();
        log.info("Guide {} revision {} {} #{} digest {}", revision.slug(), result.outcome(), revision.id(),
                revision.revisionNumber(), revision.draftDigest());
    }

    private void publish(ApplicationArguments args) {
        LocalDate verifiedOn;
        try { verifiedOn = LocalDate.parse(single(args, VERIFIED_ON)); }
        catch (DateTimeParseException invalid) { throw new IllegalArgumentException("--verified-on must be YYYY-MM-DD"); }
        var result = publications.publish(new GuidePublicationService.PublishRequest(uuid(single(args, PUBLISH)),
                single(args, EXPECT_DIGEST), current(single(args, EXPECT_CURRENT)), single(args, CHECKLIST), verifiedOn,
                flag(args, CONFIRM), operator));
        var publication = result.publication();
        log.info("Guide {} {} publication {} revision {} published digest {} at {}", publication.slug(), result.outcome(),
                publication.id(), publication.revisionId(), publication.publishedDigest(), publication.publishedAt());
    }

    private void withdraw(ApplicationArguments args) {
        var result = publications.withdraw(single(args, WITHDRAW), uuid(single(args, EXPECT_CURRENT)), single(args, REASON), operator);
        log.info("Guide {} WITHDRAWN publication {} at {}", result.publication().slug(), result.publication().id(),
                result.publication().endedAt());
    }

    private static UUID current(String value) { return "none".equals(value) ? null : uuid(value); }

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
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Expected a UUID: " + value); }
        if (!id.toString().equalsIgnoreCase(value)) { throw new IllegalArgumentException("Expected a canonical UUID: " + value); }
        return id;
    }
}
