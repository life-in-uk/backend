package info.lifeinuk.backend.guides;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

/**
 * Protects the reviewed Issue #42 Health &amp; NHS production artifacts through the production reader.
 * No Spring, database, frontend checkout or network is needed. The digest fingerprints the approved
 * canonical article (evidence links removed) for editorial fidelity only; it never supplies identity.
 */
class HealthNhsGuideImportArtifactsTest {
    static final Path DIRECTORY = Path.of("content/guides");
    static final String EVIDENCE_LINK = "\\[查看官方依据\\]\\(#guide-evidence-[a-z0-9-]+\\)";

    record Reviewed(String file, String slug, String publishedAt, int sources, int evidence, int supports, String digest) {
        @Override public String toString() { return file; }
    }

    static Stream<Reviewed> reviewed() {
        return Stream.of(
                new Reviewed("where-to-go-when-ill-england-zh.json", "where-to-go-when-ill-england", "2026-10-05T05:50:00Z",
                        13, 17, 25, "f1c7fb2d72a32cd0ec7f77abd09b8b6bd9e1fb94f4cccba2d060b09c50391451"),
                new Reviewed("nhs-ihs-costs-england-zh.json", "nhs-ihs-costs-england", "2026-10-05T05:59:00Z",
                        15, 19, 33, "8789b52e915b837ce0843c9c5304457ba3b226c2a466d11bdefad7e83a81a6b8"),
                new Reviewed("visiting-parents-healthcare-england-zh.json", "visiting-parents-healthcare-england", "2026-10-05T06:17:00Z",
                        19, 22, 34, "23f7d8eee92bf69b21a1ba5df002d186227899d726536a814516424655833a9d"),
                new Reviewed("medicines-prescriptions-england-zh.json", "medicines-prescriptions-england", "2026-10-05T06:06:00Z",
                        19, 16, 24, "74b4b3573920ebdbff0de7809b145a7a4e9f54b034e39ae800ad4b78e76eda83"),
                new Reviewed("chinese-medical-records-vaccinations-england-zh.json", "chinese-medical-records-vaccinations-england",
                        "2026-10-05T06:09:00Z", 8, 8, 9, "fb15f8bca09c8bd99b97a0e3461ccb8954c04f53fb27cbaa3aad6f2b1bee7787"),
                new Reviewed("nhs-interpreter-language-help-england-zh.json", "nhs-interpreter-language-help-england",
                        "2026-10-05T06:17:00Z", 4, 8, 9, "34b1eab0defcbf684558ec1790c436d4e2df8a7475482b12c7983a972e3744f3"),
                new Reviewed("nhs-dentist-england-zh.json", "nhs-dentist-england", "2026-10-05T06:13:00Z",
                        12, 11, 16, "a7ec1ce3791b49351f756bedd25882fa6969663babc728c0ede52ab63e5e68d5"),
                new Reviewed("urgent-dental-care-england-zh.json", "urgent-dental-care-england", "2026-10-05T06:15:00Z",
                        7, 13, 17, "a66a2d9c51a53063c022512c08e92cd31b0af855df843e2ccd434789b512873e"));
    }

    @ParameterizedTest
    @MethodSource("reviewed")
    void productionReaderValidatesReferencesAndApprovedPublicationMetadata(Reviewed reviewed) throws Exception {
        var input = new GuideImportReader().read(DIRECTORY.resolve(reviewed.file()));
        assertThat(input.slug()).isEqualTo(reviewed.slug());
        assertThat(input.category()).isEqualTo("health-nhs");
        assertThat(input.status()).isEqualTo(GuideStatus.PUBLISHED);
        assertThat(input.publishedAt()).isEqualTo(Instant.parse(reviewed.publishedAt()));
        assertThat(input.updatedAt()).isEqualTo(input.publishedAt());
        assertThat(input.sources()).hasSize(reviewed.sources());
        assertThat(input.evidence()).hasSize(reviewed.evidence());
        assertThat(input.evidence().stream().mapToInt(evidence -> evidence.supports().size()).sum()).isEqualTo(reviewed.supports());
        assertThat(input.sources()).extracting(GuideImportDefinition.Source::key).doesNotHaveDuplicates().doesNotContainNull();
        assertThat(input.evidence()).extracting(GuideImportDefinition.Evidence::key).doesNotHaveDuplicates();
        assertThat(input.sources()).allSatisfy(source -> {
            assertThat(source.url()).startsWith("https://");
            assertThat(source.accessedAt()).isBeforeOrEqualTo(input.publishedAt());
        });
        // Every listed official source is used as evidence, and every evidence record is referenced.
        var used = new HashSet<String>();
        input.evidence().forEach(evidence -> evidence.supports().forEach(support -> used.add(support.sourceKey())));
        assertThat(used).containsExactlyInAnyOrderElementsOf(input.sources().stream().map(GuideImportDefinition.Source::key).toList());
        assertThat(input.evidence()).allSatisfy(evidence ->
                assertThat(input.content()).contains("(#guide-evidence-" + evidence.key() + ")"));
    }

    @ParameterizedTest
    @MethodSource("reviewed")
    void onlyEvidenceLinksAreAddedToTheApprovedCanonicalArticle(Reviewed reviewed) throws Exception {
        var input = new GuideImportReader().read(DIRECTORY.resolve(reviewed.file()));
        String article = input.content().replaceAll(EVIDENCE_LINK, "");
        String digest = java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(article.getBytes(StandardCharsets.UTF_8)));
        assertThat(digest).isEqualTo(reviewed.digest());
        assertThat(article).startsWith("# " + input.title() + "\n");
        assertThat(article).contains("## 官方资料", "最后更新：");
        assertThat(article).doesNotContain("SYSTEM", "agent instructions", "git status", "研究包", "任务报告", "TODO");
        assertThat(input.summary()).doesNotContain("最", "！");
    }

    // Corpus-wide validation and slug uniqueness live in ProductionGuideArtifactsTest.
    @Test
    void everyReviewedHealthGuideIsPartOfTheProductionCorpus() throws IOException {
        var slugs = ProductionGuideArtifactsTest.productionSlugs();
        assertThat(slugs).contains("registering-with-a-gp-england");
        assertThat(slugs).containsAll(reviewed().map(Reviewed::slug).toList());
    }
}
