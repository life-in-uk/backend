package info.lifeinuk.backend.guides;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

/**
 * Local Family Visa drafts revised or written on 15 November 2026: the dependent-children Guide (journey
 * stage 4) and the application-process Guide (journey stage 5). The drafts are local-only (content/drafts is
 * excluded from Git), so every check is skipped when they are absent. When present, each must pass the
 * production reader, stay an unpublished DRAFT in the family-visa category, cite only official GOV.UK sources
 * (no case law), and keep evidence locators free of Chinese text because all five locales share them.
 */
class FamilyVisaDraftArtifactsTest {
    static final Path DIRECTORY = Path.of("content/drafts/family-visa");

    static Stream<String> slugs() {
        return List.of("partner-visa-dependent-children", "partner-visa-application-process").stream();
    }

    @ParameterizedTest
    @MethodSource("slugs")
    void eachRevisedDraftPassesTheProductionReaderAndCitesOnlyOfficialSources(String slug) throws Exception {
        Path file = DIRECTORY.resolve(slug + "-zh.json");
        Assumptions.assumeTrue(Files.exists(file), "local family-visa draft not present: " + file);
        var guide = new GuideImportReader().read(file);

        assertThat(guide.slug()).isEqualTo(slug);
        assertThat(guide.category()).isEqualTo("family-visa");
        assertThat(guide.status()).isEqualTo(GuideStatus.DRAFT);
        assertThat(guide.publishedAt()).isNull();
        assertThat(guide.content()).contains("核对日期：2026 年 11 月 15 日")
                .doesNotContain("OISC", "正在核对", "Buydov", "Mundeba", "TD (Yemen)");
        for (String marker : GuideQualityGate.EDITORIAL_MARKERS) {
            assertThat(guide.content()).as("editorial marker " + marker).doesNotContain(marker);
        }
        var cited = new HashSet<String>();
        guide.evidence().forEach(evidence -> {
            assertThat(evidence.supports()).as(evidence.key()).isNotEmpty();
            evidence.supports().forEach(support -> {
                cited.add(support.sourceKey());
                if (support.locator() != null) {
                    assertThat(support.locator()).as("locator " + evidence.key())
                            .doesNotContainPattern("[\\u4e00-\\u9fff\\uff00-\\uffef\\u3000-\\u303f]");
                }
            });
        });
        assertThat(guide.sources()).extracting(GuideImportDefinition.Source::key).doesNotHaveDuplicates().doesNotContainNull();
        assertThat(guide.sources()).allSatisfy(source -> {
            assertThat(source.url()).matches("https://www\\.gov\\.uk/.+");
            assertThat(cited).as("source cited: " + source.key()).contains(source.key());
        });
    }
}
