package info.lifeinuk.backend.underground;

import info.lifeinuk.backend.support.IsolatedPostgres;
import java.sql.Connection;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
class UndergroundCurrentStateMigrationTest {
    @Autowired DataSource dataSource;

    @Test
    void v4UpgradePreservesExistingSourceHistoryAndSeedsNoCurrentState() throws Exception {
        String schema = "v4_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("3").load().migrate();
        try (Connection connection = dataSource.getConnection()) {
            String original = connection.getSchema();
            try {
                connection.setSchema(schema);
                try (var statement = connection.createStatement()) {
                    statement.execute("""
                            INSERT INTO source (id,source_key,display_name,scope,enabled,version)
                            VALUES ('00000000-0000-0000-0000-000000000001','transport-for-london','Transport for London','TFL_UNDERGROUND_STATUS',true,0)
                            """);
                    statement.execute("""
                            INSERT INTO source_endpoint (id,source_id,endpoint_key,url,qualification_status,qualification_record,attribution_reference,use_retention_policy,enabled,version)
                            VALUES ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','tfl-underground-status',
                                'https://api.tfl.gov.uk/Line/Mode/tube/Status','QUALIFIED','Offline owner decision','Offline terms','Offline policy',true,0)
                            """);
                    statement.execute("""
                            INSERT INTO ingestion_run (id,source_endpoint_id,started_at,status,version)
                            VALUES ('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000002','2026-01-01T00:00:00Z','STARTED',0)
                            """);
                    statement.execute("""
                            INSERT INTO evidence_artifact (id,ingestion_run_id,payload,media_type,observed_at,sha256)
                            VALUES ('00000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000003',convert_to('[]','UTF8'),
                                'application/json','2026-01-01T00:00:01Z',encode(sha256(convert_to('[]','UTF8')),'hex'))
                            """);
                    statement.execute("UPDATE ingestion_run SET status='SUCCESS',completed_at='2026-01-01T00:00:02Z'");
                }
                String before = snapshot(connection);
                assertThat(Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("4").load().migrate().migrationsExecuted)
                        .isEqualTo(1);
                assertThat(snapshot(connection)).isEqualTo(before);
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM underground_current_snapshot")) {
                    rows.next(); assertThat(rows.getInt(1)).isZero();
                }
                assertThat(Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("4").load().migrate().migrationsExecuted)
                        .isZero();
            } finally { connection.setSchema(original); }
        }
    }

    private String snapshot(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("""
                SELECT json_build_object(
                    'source',(SELECT json_agg(row_to_json(s) ORDER BY id) FROM source s),
                    'endpoint',(SELECT json_agg(row_to_json(e) ORDER BY id) FROM source_endpoint e),
                    'run',(SELECT json_agg(row_to_json(r) ORDER BY id) FROM ingestion_run r),
                    'artifact',(SELECT json_agg(row_to_json(a) ORDER BY id) FROM evidence_artifact a))::text
                """)) {
            result.next(); return result.getString(1);
        }
    }
}
