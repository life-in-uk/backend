package info.lifeinuk.backend.guides;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** The unpublished article is local. CI still verifies the topic's review policy. */
class UniversityApplicationDraftArtifactsTest {
    private static final String SLUG = "uk-undergraduate-application-timeline";
    private static final Path DRAFT = Path.of("content", "drafts", GuideQualityGate.UNIVERSITY_CATEGORY, SLUG + "-zh.json");

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

    @Test
    void privateTimelineDraftPassesTheProductionReaderAndQualityGate() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(DRAFT), "private local timeline draft not present");
        var reader = new GuideImportReader();
        var draft = reader.read(DRAFT);
        assertThat(draft.slug()).isEqualTo(SLUG);
        assertThat(draft.category()).isEqualTo(GuideQualityGate.UNIVERSITY_CATEGORY);
        assertThat(draft.status()).isEqualTo(GuideStatus.DRAFT);
        assertThat(draft.publishedAt()).isNull();
        assertThat(draft.content()).contains("2027 年 1 月 13 日", "Clearing", GuideQualityGate.LAST_UPDATED_MARKER);
        assertThat(draft.sources()).allSatisfy(source ->
                assertThat(source.url()).matches("https://www\\.(ucas\\.com|gov\\.uk)/.+"));
        var gate = new GuideQualityGate(new GuideRevisionService(null, reader));
        var report = gate.check(GuideQualityGateTest.revision(draft), Optional.empty(), Instant.now());
        assertThat(report.checklist()).isEqualTo(GuideQualityGate.Checklist.FAMILY_VISA_V1);
        assertThat(report.findings()).isEmpty();
    }
}
