package info.lifeinuk.backend.guides;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import static info.lifeinuk.backend.guides.GuideQualityGate.Severity.*;
import static org.assertj.core.api.Assertions.*;

/** Quality-gate rules on synthetic revisions only. No Spring, database or network. */
class GuideQualityGateTest {
    static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private final GuideImportReader reader = new GuideImportReader();
    private final GuideQualityGate gate = new GuideQualityGate(new GuideRevisionService(null, reader));
    private final JsonMapper json = JsonMapper.builder().build();

    /** synthetic-evidence-import.json (DRAFT, two keyed sources, both cited) after one edit. */
    GuideImportDefinition draft(Consumer<ObjectNode> edit) throws Exception {
        var root = (ObjectNode) json.readTree(GuideContentDigestTest.fixture("synthetic-evidence-import.json"));
        edit.accept(root);
        return reader.parse(json.writeValueAsString(root).getBytes(StandardCharsets.UTF_8));
    }
    static GuideRevision revision(GuideImportDefinition draft) {
        return stored(draft, GuideContentDigest.canonicalForm(draft), GuideContentDigest.digest(draft));
    }
    static GuideRevision stored(GuideImportDefinition draft, String canonicalForm, String digest) {
        return new GuideRevision(UUID.fromString("00000000-0000-0000-0000-0000000000aa"), draft.slug(), 1,
                GuideContentDigest.VERSION, canonicalForm, digest, null, NOW, "synthetic-operator", "test", false, null);
    }
    GuideQualityGate.Report check(GuideImportDefinition draft) { return gate.check(revision(draft), Optional.empty(), NOW); }
    static java.util.List<String> codes(GuideQualityGate.Report report, GuideQualityGate.Severity severity) {
        return report.of(severity).stream().map(GuideQualityGate.Finding::code).toList();
    }
    static void addUnusedSource(ObjectNode root) {
        ((ArrayNode) root.get("sources")).addObject().put("key", "official-c").put("organisation", "Example C")
                .put("title", "Reference C").put("url", "https://example.invalid/c").put("accessedAt", "2026-10-04T10:00:00Z");
    }

    @Test
    void cleanStandardRevisionHasNoBlockingFailuresButStillNeedsTheHumanChecklist() throws Exception {
        var report = check(draft(root -> { }));
        assertThat(report.blocked()).isFalse();
        assertThat(report.checklist()).isEqualTo(GuideQualityGate.Checklist.STANDARD_V1);
        assertThat(codes(report, WARNING)).containsExactly("LAST_UPDATED_MISSING");
        String text = GuideQualityGate.render(report);
        assertThat(text).contains("synthetic-evidence-guide", "00000000-0000-0000-0000-0000000000aa",
                GuideContentDigestTest.SYNTHETIC_EVIDENCE_DIGEST, "Checklist standard-v1", "NOT checked by this tool",
                "official-a", "example-application", "RESULT: NO BLOCKING FAILURES (human checklist still required)");
    }

    @Test
    void reportNeverClaimsCurrencyApprovalOrAdviceAndPublishesNothing() throws Exception {
        String text = GuideQualityGate.render(check(draft(root -> { })));
        assertThat(text).contains("do not confirm", "not legal approval or immigration advice",
                "Human verification is required before publication", "This command publishes nothing");
        assertThat(text).doesNotContainIgnoringCase("approved").doesNotContain("PUBLISHED ")
                .doesNotContainIgnoringCase("verified as").doesNotContainIgnoringCase("sources are current");
    }

    @Test
    void editorialMarkersAnywhereAreBlockingWithTheirLocation() throws Exception {
        var report = check(draft(root -> {
            root.put("content", root.get("content").stringValue() + "\nTODO confirm wording\n");
            root.put("summary", "Offline provenance example 待核对.");
            ((ObjectNode) root.get("evidence").get(0).get("supports").get(0)).put("note", "[EDITORIAL: check] note.");
        }));
        assertThat(report.blocked()).isTrue();
        assertThat(report.of(BLOCKING)).extracting(GuideQualityGate.Finding::message).anySatisfy(m -> assertThat(m).contains("content line", "TODO"))
                .anySatisfy(m -> assertThat(m).contains("summary", "待核对"))
                .anySatisfy(m -> assertThat(m).contains("example-application support[0].note", "[EDITORIAL"));
        assertThat(GuideQualityGate.render(report)).contains("RESULT: BLOCKED");
    }

    @Test
    void unusedSourcesWarnForStandardButBlockFamilyVisa() throws Exception {
        assertThat(codes(check(draft(GuideQualityGateTest::addUnusedSource)), WARNING)).contains("SOURCE_UNUSED");
        assertThat(check(draft(GuideQualityGateTest::addUnusedSource)).blocked()).isFalse();
        var family = check(draft(root -> { addUnusedSource(root); root.put("category", "family-visa"); }));
        assertThat(codes(family, BLOCKING)).containsExactly("SOURCE_UNUSED");
        assertThat(family.checklist()).isEqualTo(GuideQualityGate.Checklist.FAMILY_VISA_V1);
    }

    @Test
    void emptyEvidenceWarnsForStandardButBlocksFamilyVisa() throws Exception {
        Consumer<ObjectNode> noEvidence = root -> {
            root.putArray("evidence");
            root.put("content", "# Example\n\nNo evidence links.\n");
            root.putArray("sources");
        };
        assertThat(codes(check(draft(noEvidence)), WARNING)).contains("EVIDENCE_EMPTY");
        assertThat(check(draft(noEvidence)).blocked()).isFalse();
        assertThat(codes(check(draft(noEvidence.andThen(root -> root.put("category", "family-visa")))), BLOCKING))
                .containsExactly("EVIDENCE_EMPTY");
    }

    @Test
    void nonHttpsAndFutureAccessedSourcesAreBlocking() throws Exception {
        var report = check(draft(root -> {
            ((ObjectNode) root.get("sources").get(0)).put("url", "http://example.invalid/b");
            ((ObjectNode) root.get("sources").get(1)).put("accessedAt", "2026-10-11T00:00:00Z");
        }));
        assertThat(codes(report, BLOCKING)).containsExactlyInAnyOrder("SOURCE_NOT_HTTPS", "SOURCE_ACCESSED_IN_FUTURE");
    }

    @Test
    void familyVisaChecklistIsStricterAndKeepsTheAdviceBoundary() throws Exception {
        var report = check(draft(root -> root.put("category", "family-visa")));
        assertThat(report.blocked()).isFalse();
        assertThat(GuideQualityGate.Checklist.STANDARD_V1.items).hasSize(4);
        assertThat(GuideQualityGate.Checklist.FAMILY_VISA_V1.items).hasSize(6);
        assertThat(GuideQualityGate.render(report)).contains("Checklist family-visa-v1", "regulated advice",
                "professional verification", "no guarantee or prediction of outcome");
    }

    @Test
    void storedTextThatIsNotExactCanonicalFormIsBlocking() throws Exception {
        var draft = draft(root -> { });
        String canonical = GuideContentDigest.canonicalForm(draft);
        String spaced = canonical.replaceFirst("\\{", "{ ");
        assertThat(codes(gate.check(stored(draft, spaced, GuideContentDigest.digest(draft)), Optional.empty(), NOW), BLOCKING))
                .contains("CANONICAL_MISMATCH");
        assertThat(codes(gate.check(stored(draft, canonical, "0".repeat(64)), Optional.empty(), NOW), BLOCKING))
                .contains("CANONICAL_MISMATCH");
        var invalid = gate.check(stored(draft, "{\"canonicalization\":\"other\"}", "0".repeat(64)), Optional.empty(), NOW);
        assertThat(codes(invalid, BLOCKING)).containsExactly("CANONICAL_INVALID");
        assertThat(GuideQualityGate.render(invalid)).contains("RESULT: BLOCKED");
    }

    @Test
    void publicStateIsReportedWithoutClaimingADiff() throws Exception {
        var report = gate.check(revision(draft(root -> { })),
                Optional.of(new GuideRevisionStore.PublicState(GuideStatus.PUBLISHED, Instant.parse("2026-10-05T00:00:00Z"))), NOW);
        assertThat(GuideQualityGate.render(report)).contains("public now:    PUBLISHED (updatedAt 2026-10-05T00:00:00Z)",
                "needs publication history");
    }
}
