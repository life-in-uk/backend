package info.lifeinuk.backend.guides;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

/**
 * Local Work Visa (Skilled Worker) Phase 1 drafts. The drafts are local-only
 * (content/drafts is excluded from Git), so every check is skipped when the
 * directory is absent. When present, each draft must pass the production
 * reader (fields, timestamps, evidence references) and stay an unpublished
 * DRAFT in the work-visa category with fully cited HTTPS sources.
 */
class WorkVisaDraftArtifactsTest {
    static final Path DIRECTORY = Path.of("content/drafts/work-visa");
    static final List<String> SLUGS = List.of(
            "skilled-worker-overview",
            "skilled-worker-sponsorship-cos",
            "skilled-worker-occupations-salary",
            "skilled-worker-english-documents",
            "skilled-worker-switch-extend-change-employer",
            "skilled-worker-dependants",
            "skilled-worker-job-loss-sponsorship-ends",
            "skilled-worker-settlement-ilr");

    static Stream<String> slugs() {
        return SLUGS.stream();
    }

    @Test
    void theDraftDirectoryHoldsExactlyTheEightPlannedGuides() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(DIRECTORY), "local work-visa drafts not present");
        try (var files = Files.list(DIRECTORY)) {
            assertThat(files.map(path -> path.getFileName().toString()).sorted())
                    .containsExactlyElementsOf(SLUGS.stream().map(slug -> slug + "-zh.json").sorted().toList());
        }
    }

    @ParameterizedTest
    @MethodSource("slugs")
    void eachDraftPassesTheProductionReaderAndStaysAnUnpublishedDraft(String slug) throws Exception {
        Path file = DIRECTORY.resolve(slug + "-zh.json");
        Assumptions.assumeTrue(Files.exists(file), "local work-visa draft not present: " + file);
        var guide = new GuideImportReader().read(file);

        assertThat(guide.slug()).isEqualTo(slug);
        assertThat(guide.category()).isEqualTo("work-visa");
        assertThat(guide.status()).isEqualTo(GuideStatus.DRAFT);
        assertThat(guide.publishedAt()).isNull();
        assertThat(guide.content()).contains(GuideQualityGate.LAST_UPDATED_MARKER).contains("核对日期：2026 年 11 月 14 日");
        for (String marker : GuideQualityGate.EDITORIAL_MARKERS) {
            assertThat(guide.content()).as("editorial marker " + marker).doesNotContain(marker);
        }

        assertThat(guide.evidence()).isNotEmpty();
        var cited = new HashSet<String>();
        guide.evidence().forEach(evidence -> {
            assertThat(evidence.supports()).as(evidence.key()).isNotEmpty();
            evidence.supports().forEach(support -> cited.add(support.sourceKey()));
        });
        assertThat(guide.sources()).extracting(GuideImportDefinition.Source::key).doesNotHaveDuplicates().doesNotContainNull();
        assertThat(guide.sources()).allSatisfy(source -> {
            assertThat(source.url()).matches("https://www\\.(gov|legislation\\.gov)\\.uk/.+");
            assertThat(cited).as("source cited: " + source.key()).contains(source.key());
        });
    }
}
