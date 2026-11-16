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
 * Local Renting & Moving In drafts (England, letting-agency lets): Phase 1
 * (finding a home to signing, checked 15 November 2026) and Phase 2 (moving
 * in and household bills, checked 16 November 2026). The drafts are local-only
 * (content/drafts is excluded from Git), so every check is skipped when the
 * directory is absent. When present, each draft must pass the production
 * reader (fields, timestamps, evidence references) and stay an unpublished
 * DRAFT in the renting category with fully cited HTTPS sources from GOV.UK,
 * Shelter England, Citizens Advice or, for Phase 2 household bills, the
 * regulators Ofgem and Ofcom and the statutory water consumer body CCW.
 */
class RentingDraftArtifactsTest {
    static final Path DIRECTORY = Path.of("content/drafts/renting");
    static final List<String> PHASE_1 = List.of(
            "renting-letting-agent-overview",
            "renting-finding-viewing",
            "renting-referencing-checks",
            "renting-guarantors",
            "renting-deposits-fees",
            "renting-tenancy-agreement");
    static final List<String> PHASE_2 = List.of(
            "renting-moving-in-inventory",
            "renting-council-tax",
            "renting-energy",
            "renting-water",
            "renting-broadband",
            "renting-tv-licence");
    static final List<String> SLUGS = Stream.concat(PHASE_1.stream(), PHASE_2.stream()).toList();

    static Stream<String> slugs() {
        return SLUGS.stream();
    }

    @Test
    void theDraftDirectoryHoldsExactlyThePlannedGuides() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(DIRECTORY), "local renting drafts not present");
        try (var files = Files.list(DIRECTORY)) {
            assertThat(files.map(path -> path.getFileName().toString()).sorted())
                    .containsExactlyElementsOf(SLUGS.stream().map(slug -> slug + "-zh.json").sorted().toList());
        }
    }

    @ParameterizedTest
    @MethodSource("slugs")
    void eachDraftPassesTheProductionReaderAndStaysAnUnpublishedDraft(String slug) throws Exception {
        Path file = DIRECTORY.resolve(slug + "-zh.json");
        Assumptions.assumeTrue(Files.exists(file), "local renting draft not present: " + file);
        var guide = new GuideImportReader().read(file);

        assertThat(guide.slug()).isEqualTo(slug);
        assertThat(guide.category()).isEqualTo("renting");
        assertThat(guide.status()).isEqualTo(GuideStatus.DRAFT);
        assertThat(guide.publishedAt()).isNull();
        assertThat(guide.content()).contains(GuideQualityGate.LAST_UPDATED_MARKER).contains(PHASE_2.contains(slug) ? "核对日期：2026 年 11 月 16 日" : "核对日期：2026 年 11 月 15 日");
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
            assertThat(source.url()).matches(PHASE_2.contains(slug)
                    ? "https://(www\\.gov\\.uk|england\\.shelter\\.org\\.uk|www\\.citizensadvice\\.org\\.uk|www\\.ofgem\\.gov\\.uk|www\\.ofcom\\.org\\.uk|www\\.ccw\\.org\\.uk)/.+"
                    : "https://(www\\.gov\\.uk|england\\.shelter\\.org\\.uk|www\\.citizensadvice\\.org\\.uk)/.+");
            assertThat(cited).as("source cited: " + source.key()).contains(source.key());
        });
        // Locators are shared by all five locales, so they must not contain Chinese text.
        guide.evidence().forEach(evidence -> evidence.supports().forEach(support -> {
            if (support.locator() != null) {
                assertThat(support.locator()).as("locator " + evidence.key()).doesNotContainPattern("[\\u4e00-\\u9fff\\uff00-\\uffef]");
            }
        }));
    }
}
