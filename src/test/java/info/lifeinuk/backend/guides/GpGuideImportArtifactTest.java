package info.lifeinuk.backend.guides;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Protect the reviewed production artifact; no Spring, database, frontend checkout or network needed. */
class GpGuideImportArtifactTest {
    static final Path ARTIFACT = Path.of("content/guides/registering-with-a-gp-england-zh.json");

    @Test
    void productionReaderValidatesAllReferencesAndApprovedPublicationMetadata() throws Exception {
        var input = new GuideImportReader().read(ARTIFACT);
        assertThat(input.slug()).isEqualTo("registering-with-a-gp-england");
        assertThat(input.category()).isEqualTo("health-nhs");
        assertThat(input.title()).isEqualTo("刚到英国怎么注册 GP？没有地址证明、NHS Number 也可以吗？");
        assertThat(input.summary()).isEqualTo("在英格兰注册 GP 的申请方式、材料要求、被拒后的处理办法，以及临时注册和口译服务。");
        assertThat(input.status()).isEqualTo(GuideStatus.PUBLISHED);
        assertThat(input.publishedAt()).isEqualTo(Instant.parse("2026-10-04T21:30:00Z"));
        assertThat(input.updatedAt()).isEqualTo(input.publishedAt());
        assertThat(input.sources()).hasSize(9);
        assertThat(input.evidence()).hasSize(17);
        assertThat(input.sources()).extracting(GuideImportDefinition.Source::key).doesNotHaveDuplicates().doesNotContainNull();
        assertThat(input.evidence()).extracting(GuideImportDefinition.Evidence::key).doesNotHaveDuplicates();
        assertThat(input.sources()).allSatisfy(source -> assertThat(source.url()).startsWith("https://"));
    }

    @Test
    void onlyEvidenceLinksAreAddedToTheCorrectedCanonicalPublishableArticle() throws Exception {
        var input = new GuideImportReader().read(ARTIFACT);
        String article = input.content().replaceAll("\\[查看官方依据\\]\\(#guide-evidence-[a-z0-9-]+\\)", "");
        // This fingerprint checks editorial fidelity, never supplies source/evidence identity.
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(article.getBytes(StandardCharsets.UTF_8)));
        assertThat(digest).isEqualTo("4c776b18e5cee4ccea4516a60b59a94615c45f5d77940e0361b5e833a3ddefc7");
        assertThat(article).doesNotContain("- 用 NHS App；", "3. 在线、用 NHS App", "- 你以前被这家诊所从病人名单上除名过。",
                "SYSTEM", "agent instructions", "git status", "研究包", "任务报告");
        assertThat(article).contains("3. 在线或去前台填表申请。", "## 官方资料", "最后更新：2026 年 10 月 4 日");
        // NHS App remains legitimate after registration; the approved corrections are intentionally narrow.
        assertThat(article).contains("以后忘了，可以在 NHS App 里看", "诊所网站、NHS App 在线联系");
    }
}
