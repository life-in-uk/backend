package info.lifeinuk.backend.source;

import info.lifeinuk.backend.support.IsolatedPostgres;
import java.sql.Connection;
import java.sql.SQLException;
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
class NationalHighwaysSourceMigrationTest {
    @Autowired DataSource dataSource;

    private Flyway flyway(String schema, String target) {
        return Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target(target).load();
    }

    @Test
    void v5UpgradePreservesExistingProvidersHistoryAndStateAndSeedsNothing() throws Exception {
        String schema = "v5_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        flyway(schema, "4").migrate();
        try (Connection connection = dataSource.getConnection()) {
            String original = connection.getSchema();
            try {
                connection.setSchema(schema);
                try (var statement = connection.createStatement()) {
                    statement.execute("""
                            INSERT INTO source VALUES
                              ('00000000-0000-0000-0000-000000000001','gov-uk-bank-holidays','GOV.UK Bank Holidays','UK_BANK_HOLIDAYS',false,3),
                              ('00000000-0000-0000-0000-000000000011','transport-for-london','Transport for London','TFL_UNDERGROUND_STATUS',true,1)
                            """);
                    statement.execute("""
                            INSERT INTO source_endpoint VALUES ('00000000-0000-0000-0000-000000000002',
                                '00000000-0000-0000-0000-000000000001', 'gov-uk-bank-holidays-json',
                                'https://www.gov.uk/bank-holidays.json', 'PENDING', NULL,
                                'https://www.gov.uk/bank-holidays', 'Existing Bank Holidays policy',
                                false, 'CURRENT_YEAR', 86400, '2030-01-01T00:00:00Z', 4)
                            """);
                    statement.execute("""
                            INSERT INTO source_endpoint (id,source_id,endpoint_key,url,qualification_status,qualification_record,attribution_reference,use_retention_policy,enabled,version)
                            VALUES ('00000000-0000-0000-0000-000000000012','00000000-0000-0000-0000-000000000011','tfl-underground-status',
                                'https://api.tfl.gov.uk/Line/Mode/tube/Status','QUALIFIED','Offline owner decision','Offline terms','Offline policy',true,2)
                            """);
                    statement.execute("""
                            INSERT INTO ingestion_run (id,source_endpoint_id,started_at,status,version)
                            VALUES ('00000000-0000-0000-0000-000000000013','00000000-0000-0000-0000-000000000012','2026-01-01T00:00:00Z','STARTED',0)
                            """);
                    statement.execute("""
                            INSERT INTO evidence_artifact (id,ingestion_run_id,payload,media_type,observed_at,sha256)
                            VALUES ('00000000-0000-0000-0000-000000000014','00000000-0000-0000-0000-000000000013',convert_to('[]','UTF8'),
                                'application/json','2026-01-01T00:00:01Z',encode(sha256(convert_to('[]','UTF8')),'hex'))
                            """);
                    statement.execute("UPDATE ingestion_run SET status='SUCCESS',completed_at='2026-01-01T00:00:02Z'");
                    statement.execute("INSERT INTO underground_current_snapshot VALUES (1,'00000000-0000-0000-0000-000000000014',1767225601,0)");
                }
                String before = snapshot(connection);
                assertThat(flyway(schema, "5").migrate().migrationsExecuted).isEqualTo(1);
                assertThat(snapshot(connection)).isEqualTo(before);
                assertThat(count(connection, "SELECT count(*) FROM source WHERE source_key='national-highways'")).isZero();
                assertThat(count(connection, "SELECT count(*) FROM source_endpoint WHERE endpoint_key='national-highways-road-closures'")).isZero();
                assertThat(flyway(schema, "5").migrate().migrationsExecuted).isZero();

                try (var statement = connection.createStatement()) {
                    // The new pair/scope is valid individually, but existing identities still cannot be rewritten into it.
                    assertThatThrownBy(() -> statement.execute("""
                            UPDATE source_endpoint SET endpoint_key='national-highways-road-closures',
                                url='https://api.data.nationalhighways.co.uk/roads/v2.0/closures'
                            WHERE endpoint_key='tfl-underground-status'
                            """)).isInstanceOf(SQLException.class).hasMessageContaining("Endpoint key and URL cannot change");
                    assertThatThrownBy(() -> statement.execute("""
                            UPDATE source SET source_key='national-highways', scope='NATIONAL_HIGHWAYS_ROAD_CLOSURES'
                            WHERE source_key='transport-for-london'
                            """)).isInstanceOf(SQLException.class).hasMessageContaining("Source scope cannot change");
                }
                assertThat(snapshot(connection)).isEqualTo(before);
            } finally {
                connection.setSchema(original);
            }
        }
    }

    private static int count(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static String snapshot(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("""
                SELECT json_build_object(
                    'source', (SELECT json_agg(row_to_json(s) ORDER BY id) FROM source s),
                    'endpoint', (SELECT json_agg(row_to_json(e) ORDER BY id) FROM source_endpoint e),
                    'run', (SELECT json_agg(row_to_json(r) ORDER BY id) FROM ingestion_run r),
                    'artifact', (SELECT json_agg(row_to_json(a) ORDER BY id) FROM evidence_artifact a),
                    'current', (SELECT json_agg(row_to_json(c)) FROM underground_current_snapshot c))::text
                """)) {
            result.next();
            return result.getString(1);
        }
    }
}
