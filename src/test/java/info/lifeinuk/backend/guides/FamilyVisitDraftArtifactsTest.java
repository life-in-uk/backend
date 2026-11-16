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
 * Local family-visit draft F2 (family members visiting the UK: Standard
 * Visitor visa, ETA or neither; checked 16 November 2026). It is published in
 * the family-visa category but kept apart from the frozen partner-visa drafts
 * in content/drafts/family-visa. Drafts are local-only (content/drafts is
 * excluded from Git), so every check is skipped when the directory is absent.
 * When present, the draft must pass the production reader and stay an
 * unpublished DRAFT, cited only from GOV.UK (Immigration Rules, Home Office
 * and UKVI guidance published there) and the NHS website.
 */
class FamilyVisitDraftArtifactsTest {
    static final Path DIRECTORY = Path.of("content/drafts/family-visit");
    static final List<String> SLUGS = List.of("visiting-family-uk-visitor-visa-eta");

    static Stream<String> slugs() {
        return SLUGS.stream();
    }

    @Test
    void theDraftDirectoryHoldsExactlyThePlannedGuides() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(DIRECTORY), "local family-visit drafts not present");
        try (var files = Files.list(DIRECTORY)) {
            assertThat(files.map(path -> path.getFileName().toString()).sorted())
                    .containsExactlyElementsOf(SLUGS.stream().map(slug -> slug + "-zh.json").sorted().toList());
        }
    }

    @ParameterizedTest
    @MethodSource("slugs")
    void eachDraftPassesTheProductionReaderAndStaysAnUnpublishedDraft(String slug) throws Exception {
        Path file = DIRECTORY.resolve(slug + "-zh.json");
        Assumptions.assumeTrue(Files.exists(file), "local family-visit draft not present: " + file);
        var guide = new GuideImportReader().read(file);

        assertThat(guide.slug()).isEqualTo(slug);
        assertThat(guide.category()).isEqualTo("family-visa");
        assertThat(guide.status()).isEqualTo(GuideStatus.DRAFT);
        assertThat(guide.publishedAt()).isNull();
        assertThat(guide.content()).contains(GuideQualityGate.LAST_UPDATED_MARKER).contains("核对日期：2026 年 11 月 16 日");
        for (String marker : GuideQualityGate.EDITORIAL_MARKERS) {
            assertThat(guide.content()).as("editorial marker " + marker).doesNotContain(marker);
        }
        var cited = new HashSet<String>();
        guide.evidence().forEach(evidence -> {
            assertThat(evidence.supports()).as(evidence.key()).isNotEmpty();
            evidence.supports().forEach(support -> cited.add(support.sourceKey()));
        });
        assertThat(guide.sources()).extracting(GuideImportDefinition.Source::key).doesNotHaveDuplicates().doesNotContainNull();
        assertThat(guide.sources()).allSatisfy(source -> {
            assertThat(source.url()).matches("https://(www\\.gov\\.uk|www\\.nhs\\.uk)/.+");
            assertThat(cited).as("source cited: " + source.key()).contains(source.key());
        });
        guide.evidence().forEach(evidence -> evidence.supports().forEach(support -> {
            if (support.locator() != null) {
                assertThat(support.locator()).as("locator " + evidence.key()).doesNotContainPattern("[\\u4e00-\\u9fff\\uff00-\\uffef]");
            }
        }));
    }
}
