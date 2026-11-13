package info.lifeinuk.backend.guides;

import info.lifeinuk.backend.support.IsolatedPostgres;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Explicit body redaction and runtime-role denial on disposable PostgreSQL schemas only. */
@SpringBootTest(properties = {"spring.flyway.schemas=guide_revision_retention_tests",
    "spring.flyway.default-schema=guide_revision_retention_tests",
    "spring.jpa.properties.hibernate.default_schema=guide_revision_retention_tests",
    "spring.datasource.hikari.schema=guide_revision_retention_tests", "LIFE_IN_UK_OPERATOR=synthetic-operator"})
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
class GuideRevisionRetentionTest {
    private static final String OPERATOR = "synthetic-operator";
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired GuideImportReader reader;
    @Autowired GuideRevisionService revisions;
    @Autowired GuidePublicationService publications;
    @Autowired GuidePublicationStore publicationStore;
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void clear() {
        jdbc.execute("TRUNCATE guide_revision_body_redaction, guide_locale_current, guide_locale_revision, guide_content_batch, guide_publication, guide_workflow_activation, guide_revision, guide_evidence_support, guide_evidence, guide_source, guide");
    }

    private GuideImportDefinition draft(String title) throws Exception {
        var root = (tools.jackson.databind.node.ObjectNode) json.readTree(GuideContentDigestTest.fixture("synthetic-evidence-import.json"));
        if (title != null) root.put("title", title);
        return reader.parse(json.writeValueAsString(root).getBytes(StandardCharsets.UTF_8));
    }

    private GuidePublicationStore.Publication publish(GuideRevision revision, UUID current) {
        var result = publications.publish(new GuidePublicationService.PublishRequest(revision.id(), revision.draftDigest(), current,
                "standard-v1", LocalDate.now(ZoneOffset.UTC), true, OPERATOR));
        return result.publication();
    }

    @Test
    void explicitOwnerProcedureRedactsOnlyReplacedBodyAndPreservesIntegrityMetadata() throws Exception {
        jdbc.update("INSERT INTO guide_workflow_activation(activated_by) VALUES (?)", "synthetic-owner");
        var firstDraft = draft(null);
        var firstRevision = revisions.create(firstDraft, null, OPERATOR, false, null).revision();
        var firstPublication = publish(firstRevision, null);
        var secondRevision = revisions.create(draft("Synthetic replacement"), firstRevision.id(), OPERATOR, false, null).revision();
        var replacement = publish(secondRevision, firstPublication.id());

        jdbc.execute("CALL redact_superseded_guide_body('" + firstRevision.id() + "', 'synthetic-retention-owner', 'Synthetic replacement verified')");

        assertThat(jdbc.queryForObject("SELECT current_setting('life_in_uk.guide_body_redaction', true)", String.class))
                .isNotEqualTo("on");

        var redacted = revisions.find(firstRevision.id()).orElseThrow();
        assertThat(redacted.canonicalForm()).isNull();
        assertThat(redacted.draftDigest()).isEqualTo(firstRevision.draftDigest());
        assertThat(jdbc.queryForObject("SELECT body_redacted_at IS NOT NULL FROM guide_revision WHERE id = ?", Boolean.class,
                firstRevision.id())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_revision_body_redaction WHERE revision_id = ?", Integer.class,
                firstRevision.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT replacement_publication_id FROM guide_revision_body_redaction WHERE revision_id = ?",
                UUID.class, firstRevision.id())).isEqualTo(replacement.id());
        assertThat(publicationStore.open(firstRevision.slug()).orElseThrow()).isEqualTo(replacement);
        assertThatThrownBy(() -> revisions.definition(redacted.canonicalForm())).hasMessageContaining("redacted after successful replacement");
        assertThatThrownBy(() -> jdbc.update("UPDATE guide_revision_body_redaction SET reason='edited' WHERE revision_id=?", firstRevision.id()))
                .hasMessageContaining("append-only");
    }

    @Test
    void activeOrUnreplacedRevisionCannotBeRedacted() throws Exception {
        jdbc.update("INSERT INTO guide_workflow_activation(activated_by) VALUES (?)", "synthetic-owner");
        var revision = revisions.create(draft(null), null, OPERATOR, false, null).revision();
        publish(revision, null);

        assertThatThrownBy(() -> jdbc.execute("CALL redact_superseded_guide_body('" + revision.id()
                + "', 'synthetic-retention-owner', 'not superseded')"))
                .hasMessageContaining("not safely superseded");
        assertThat(revisions.find(revision.id()).orElseThrow().canonicalForm()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_revision_body_redaction", Integer.class)).isZero();
    }

    @Test
    void nonOwnerRuntimeRoleCannotWriteProjectionOrExecuteBodyRedaction() throws Exception {
        String role = "guide_rt_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE ROLE " + role + " NOLOGIN");
        try {
            assertThat(jdbc.queryForObject("SELECT prosecdef FROM pg_proc WHERE proname='redact_superseded_guide_body'", Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("SELECT proconfig[1] FROM pg_proc WHERE proname='redact_superseded_guide_body'", String.class))
                    .isEqualTo("search_path=pg_catalog, guide_revision_retention_tests, pg_temp");
            assertThat(jdbc.queryForObject("SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid='guide_revision'::regclass", String.class))
                    .isEqualTo("life_in_uk_test");
            for (String table : new String[]{"guide_revision", "guide_content_batch", "guide_locale_revision",
                    "guide_publication", "guide_locale_current"}) {
                assertThat(jdbc.queryForObject("SELECT has_table_privilege(?, ?, 'INSERT,UPDATE,DELETE')", Boolean.class,
                        role, "guide_revision_retention_tests." + table)).as("runtime must not directly mutate %s", table).isFalse();
            }
            jdbc.execute("GRANT USAGE ON SCHEMA guide_revision_retention_tests TO " + role);
            jdbc.execute("GRANT SELECT ON guide, guide_source, guide_evidence, guide_evidence_support TO " + role);
            jdbc.execute("GRANT INSERT ON guide_revision_body_redaction TO " + role);
            try (var owner = dataSource.getConnection();
                 var connection = java.sql.DriverManager.getConnection(owner.getMetaData().getURL(), "life_in_uk_test", "");
                 var statement = connection.createStatement()) {
                statement.execute("SET search_path TO guide_revision_retention_tests");
                statement.execute("SET ROLE " + role);
                try (var rs = statement.executeQuery("SELECT * FROM guide")) { assertThat(rs.next()).isFalse(); }
                assertThatThrownBy(() -> statement.execute("UPDATE guide SET title='blocked'"))
                        .hasMessageContaining("permission denied");
                statement.execute("SELECT set_config('life_in_uk.guide_publication','on',false)");
                assertThatThrownBy(() -> statement.execute("UPDATE guide SET title='blocked'"))
                        .hasMessageContaining("permission denied");
                assertThatThrownBy(() -> statement.execute("CALL redact_superseded_guide_body('00000000-0000-0000-0000-000000000000','x','x')"))
                        .hasMessageContaining("permission denied");
                statement.execute("SELECT set_config('life_in_uk.guide_body_redaction','on',false)");
                assertThatThrownBy(() -> statement.execute("INSERT INTO guide_revision_body_redaction(revision_id,slug,draft_digest,replaced_publication_id,replacement_publication_id,redacted_by,reason) VALUES ('00000000-0000-0000-0000-000000000001','test','" + "0".repeat(64) + "', '00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000003','test','test')"))
                        .hasMessageContaining("controlled redaction procedure");
            }
        } finally {
            jdbc.execute("DROP OWNED BY " + role);
            jdbc.execute("DROP ROLE " + role);
        }
    }
}
