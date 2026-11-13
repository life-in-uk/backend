package info.lifeinuk.backend.guides;

import info.lifeinuk.backend.support.IsolatedPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Baseline and activation regressions run only in an isolated PostgreSQL schema with synthetic copies. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.flyway.schemas=guide_activation_tests", "spring.flyway.default-schema=guide_activation_tests",
    "spring.jpa.properties.hibernate.default_schema=guide_activation_tests", "spring.datasource.hikari.schema=guide_activation_tests",
    "LIFE_IN_UK_OPERATOR=synthetic-operator"
})
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
class GuideActivationTest {
    private static final String OPERATOR = "synthetic-operator";
    @Autowired GuideActivation activation;
    @Autowired GuideImporter importer;
    @Autowired GuideImportReader reader;
    @Autowired GuideQuery query;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;
    @LocalServerPort int port;
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void clear() {
        jdbc.execute("TRUNCATE guide_revision_body_redaction, guide_locale_current, guide_locale_revision, guide_content_batch, guide_publication, guide_workflow_activation, guide_revision, guide_evidence_support, guide_evidence, guide_source, guide");
    }

    private GuideImportDefinition read(Path path) throws Exception {
        return reader.read(path);
    }

    private List<Path> legacyArtifacts() throws Exception {
        try (var files = Files.list(Path.of("content/guides"))) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void allTenLegacyArtifactsBaselineIdempotentlyAndActivationPreservesPublicResponses() throws Exception {
        var artifacts = legacyArtifacts().stream().map(path -> {
            try { return read(path); } catch (Exception e) { throw new RuntimeException(e); }
        }).toList();
        assertThat(artifacts).hasSize(10).allSatisfy(artifact -> assertThat(artifact.status()).isEqualTo(GuideStatus.PUBLISHED));
        artifacts.forEach(importer::apply); // Synthetic copy of the checked-in published Guide artifacts.

        var beforeList = get("/api/guides");
        assertThat(beforeList.statusCode()).isEqualTo(200);
        var beforeDetails = artifacts.stream().map(artifact -> {
            try { return get("/api/guides/" + artifact.slug()); } catch (Exception e) { throw new RuntimeException(e); }
        }).toList();
        assertThat(beforeDetails).allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(200));

        for (var artifact : artifacts) {
            var publicationId = activation.baseline(artifact, OPERATOR);
            assertThat(activation.baseline(artifact, OPERATOR)).isEqualTo(publicationId);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_revision", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_publication WHERE legacy AND ended_at IS NULL", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_locale_current", Integer.class)).isZero();
        assertThat(activation.verify()).isEqualTo(new GuideActivation.Verification(false, 10));

        assertThat(activation.activate(OPERATOR, true)).isEqualTo(new GuideActivation.Verification(true, 10));
        assertThat(activation.verify()).isEqualTo(new GuideActivation.Verification(true, 10));
        assertThat(get("/api/guides").body()).isEqualTo(beforeList.body());
        for (int i = 0; i < artifacts.size(); i++) {
            assertThat(get("/api/guides/" + artifacts.get(i).slug()).body()).isEqualTo(beforeDetails.get(i).body());
        }
        assertThat(importer.apply(artifacts.getFirst()).outcome()).isEqualTo(GuideImporter.Outcome.UNCHANGED);
        assertThatThrownBy(() -> jdbc.update("UPDATE guide SET title = 'unauthorized' WHERE slug = ?", artifacts.getFirst().slug()))
                .hasMessageContaining("Guide projection writes require GuidePublicationService");
    }

    @Test
    void activationRejectsMissingBaselineAndArtifactMismatchWithoutChangingTheProjection() throws Exception {
        var artifacts = legacyArtifacts().stream().map(path -> {
            try { return read(path); } catch (Exception e) { throw new RuntimeException(e); }
        }).toList();
        artifacts.forEach(importer::apply);
        String before = get("/api/guides").body();
        for (var artifact : artifacts.subList(0, artifacts.size() - 1)) activation.baseline(artifact, OPERATOR);

        assertThatThrownBy(() -> activation.activate(OPERATOR, true)).hasMessageContaining("Missing open publication");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_workflow_activation", Integer.class)).isZero();
        var last = artifacts.getLast();
        var changed = new GuideImportDefinition(last.slug(), last.category(), last.title() + " altered", last.summary(),
                last.content(), last.status(), last.publishedAt(), last.updatedAt(), last.sources(), last.evidence());
        assertThatThrownBy(() -> activation.baseline(changed, OPERATOR)).hasMessageContaining("does not match live projection");
        assertThatThrownBy(() -> activation.activate(OPERATOR, false)).hasMessageContaining("explicit --confirm");
    }

    @Test
    void repeatableReadWriterStartedBeforeActivationCannotCrossTheFence() throws Exception {
        var artifact = read(Path.of("content/guides/open-uk-bank-account-new-arrival-zh.json"));
        importer.apply(artifact);
        activation.baseline(artifact, OPERATOR);
        try (var admin = dataSource.getConnection();
             var old = java.sql.DriverManager.getConnection(admin.getMetaData().getURL(), "life_in_uk_test", "")) {
            old.setSchema("guide_activation_tests");
            old.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            old.setAutoCommit(false);
            try (var statement = old.createStatement()) {
                try (var rs = statement.executeQuery("SELECT count(*) FROM guide_workflow_activation")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isZero();
                }
                activation.activate(OPERATOR, true);
                assertThatThrownBy(() -> statement.executeUpdate("UPDATE guide SET title='stale snapshot write'"))
                        .isInstanceOf(java.sql.SQLException.class)
                        .satisfies(error -> assertThat(((java.sql.SQLException) error).getSQLState()).isIn("23514", "40001"));
            } finally {
                old.rollback();
            }
        }
        assertThat(query.detail(artifact.slug()).orElseThrow().title()).isEqualTo(artifact.title());
    }
}
