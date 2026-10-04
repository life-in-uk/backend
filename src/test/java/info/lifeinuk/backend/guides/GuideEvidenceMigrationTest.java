package info.lifeinuk.backend.guides;

import info.lifeinuk.backend.support.IsolatedPostgres;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ContextConfiguration(initializers=IsolatedPostgres.Initializer.class)
class GuideEvidenceMigrationTest {
    @Autowired DataSource dataSource;
    @Test void v8UpgradePreservesLegacyGuidesAndSourcesWithoutFabricatingEvidence() throws Exception {
        String schema="v9_upgrade_"+UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("8").load().migrate();
        try(var connection=dataSource.getConnection()) {
            String original=connection.getSchema();
            try {
                connection.setSchema(schema);
                try(var sql=connection.createStatement()) {
                    sql.execute("INSERT INTO guide VALUES ('00000000-0000-0000-0000-000000000001','legacy','example','Legacy title','Summary','# Markdown','PUBLISHED','2026-10-04T12:00:00Z','2026-10-04T12:00:00Z')");
                    sql.execute("INSERT INTO guide_source VALUES ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','Example','Reference','https://example.invalid','2026-10-04T10:00:00Z',0)");
                }
                String before=snapshot(connection);
                assertThat(Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("9").load().migrate().migrationsExecuted).isEqualTo(1);
                assertThat(snapshot(connection)).isEqualTo(before);
                try(var sql=connection.createStatement();var result=sql.executeQuery("SELECT (SELECT count(*) FROM guide_evidence),(SELECT count(*) FROM guide_evidence_support),(SELECT count(*) FROM guide_source WHERE editorial_key IS NOT NULL)")) {
                    result.next();assertThat(result.getInt(1)).isZero();assertThat(result.getInt(2)).isZero();assertThat(result.getInt(3)).isZero();
                }
                assertThat(Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("9").load().migrate().migrationsExecuted).isZero();
            } finally {connection.setSchema(original);}
        }
    }
    String snapshot(java.sql.Connection connection) throws Exception {
        try(var sql=connection.createStatement();var result=sql.executeQuery("""
          SELECT json_build_object('guides',(SELECT json_agg(row_to_json(g) ORDER BY id) FROM guide g),
          'sources',(SELECT json_agg(row_to_json(s) ORDER BY id) FROM
             (SELECT id,guide_id,organisation,title,url,accessed_at,source_order FROM guide_source) s))::text
          """)) {result.next();return result.getString(1);}
    }
}
