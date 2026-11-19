package info.lifeinuk.backend.guides;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

/** The unpublished articles are local. CI still verifies the topic's review policy. */
class UniversityApplicationDraftArtifactsTest {
    private static final Path DRAFTS = Path.of("content", "drafts", GuideQualityGate.UNIVERSITY_CATEGORY);
    private static final Set<String> OFFICIAL_SOURCE_HOSTS = Set.of(
            "www.ucas.com", "www.gov.uk", "www.aqa.org.uk");
    private static final Pattern EVIDENCE_LINK = Pattern.compile("\\]\\(#guide-evidence-([a-z0-9-]+)\\)");

    @Test
    void sharedTopicUsesTheStricterEvidenceChecklist() throws Exception {
        var fixture = new GuideQualityGateTest();
        var unused = fixture.check(fixture.draft(root -> {
            root.put("category", GuideQualityGate.UNIVERSITY_CATEGORY);
            GuideQualityGateTest.addUnusedSource(root);
        }));
        assertThat(unused.checklist()).isEqualTo(GuideQualityGate.Checklist.FAMILY_VISA_V1);
        assertThat(GuideQualityGateTest.codes(unused, GuideQualityGate.Severity.BLOCKING)).containsExactly("SOURCE_UNUSED");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "uk-undergraduate-application-timeline",
            "ucas-undergraduate-application-course-choices",
            "uk-undergraduate-entry-requirements-predicted-grades",
            "ucas-personal-statement-three-questions",
            "ucas-offers-results-day-clearing",
            "student-finance-england-undergraduate",
            "uk-student-visa-application",
            "student-visa-cas-financial-english-evidence",
            "student-visa-after-approval-arrival-enrolment"
    })
    void privateDraftPassesTheProductionReaderAndQualityGate(String slug) throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(DRAFTS), "private local university drafts not present");
        var reader = new GuideImportReader();
        var draft = reader.read(DRAFTS.resolve(slug + "-zh.json"));
        assertThat(draft.slug()).isEqualTo(slug);
        assertThat(draft.category()).isEqualTo(GuideQualityGate.UNIVERSITY_CATEGORY);
        assertThat(draft.status()).isEqualTo(GuideStatus.DRAFT);
        assertThat(draft.publishedAt()).isNull();
        assertThat(draft.content()).contains(GuideQualityGate.LAST_UPDATED_MARKER);
        assertThat(draft.sources()).allSatisfy(source ->
                assertThat(URI.create(source.url()).getHost()).isIn(OFFICIAL_SOURCE_HOSTS));
        var linkedEvidence = EVIDENCE_LINK.matcher(draft.content()).results()
                .map(match -> match.group(1)).distinct().toList();
        assertThat(draft.evidence()).extracting(GuideImportDefinition.Evidence::key)
                .containsExactlyInAnyOrderElementsOf(linkedEvidence);
        var gate = new GuideQualityGate(new GuideRevisionService(null, reader));
        var report = gate.check(GuideQualityGateTest.revision(draft), Optional.empty(), Instant.now());
        assertThat(report.checklist()).isEqualTo(GuideQualityGate.Checklist.FAMILY_VISA_V1);
        assertThat(report.findings()).isEmpty();
    }
}
