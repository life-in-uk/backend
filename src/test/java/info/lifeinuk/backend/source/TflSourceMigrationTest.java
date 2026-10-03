package info.lifeinuk.backend.source;

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
class TflSourceMigrationTest {
    @Autowired DataSource dataSource;

    @Test
    void forwardMigrationPreservesExistingBankHolidaysConfigurationAndEvidence() throws Exception {
        String schema = "v3_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("2").load().migrate();
        String before;
        try (Connection connection = dataSource.getConnection()) {
            String originalSchema = connection.getSchema();
            try {
                connection.setSchema(schema);
                try (var statement = connection.createStatement()) {
                    statement.execute("""
                            INSERT INTO source VALUES ('00000000-0000-0000-0000-000000000001',
                                'gov-uk-bank-holidays', 'GOV.UK Bank Holidays', 'UK_BANK_HOLIDAYS', false, 3)
                            """);
                    statement.execute("""
                            INSERT INTO source_endpoint VALUES ('00000000-0000-0000-0000-000000000002',
                                '00000000-0000-0000-0000-000000000001', 'gov-uk-bank-holidays-json',
                                'https://www.gov.uk/bank-holidays.json', 'QUALIFIED', 'Existing owner approval',
                                'https://www.gov.uk/bank-holidays', 'Existing long-lived Bank Holidays policy',
                                false, 'CURRENT_YEAR', 86400, '2030-01-01T00:00:00Z', 4)
                            """);
                    // Offline historical fixture. Temporarily enable only to satisfy the accepted insert gate.
                    statement.execute("UPDATE source SET enabled=true");
                    statement.execute("UPDATE source_endpoint SET enabled=true");
                    statement.execute("""
                            INSERT INTO ingestion_run VALUES ('00000000-0000-0000-0000-000000000003',
                                '00000000-0000-0000-0000-000000000002', '2026-01-01T00:00:00Z',
                                '2026-01-01T00:00:02Z', 'SUCCESS', NULL, NULL, 0)
                            """);
                    statement.execute("""
                            INSERT INTO evidence_artifact VALUES ('00000000-0000-0000-0000-000000000004',
                                '00000000-0000-0000-0000-000000000003', convert_to('{}','UTF8'),
                                'application/json', '2026-01-01T00:00:01Z', encode(sha256(convert_to('{}','UTF8')),'hex'))
                            """);
                    statement.execute("UPDATE source SET enabled=false");
                    statement.execute("UPDATE source_endpoint SET enabled=false");
                }
                before = snapshot(connection);
            } finally {
                connection.setSchema(originalSchema);
            }
        }
        assertThat(Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate().migrationsExecuted)
                .isEqualTo(1);
        try (Connection connection = dataSource.getConnection()) {
            String originalSchema = connection.getSchema();
            try {
                connection.setSchema(schema);
                assertThat(snapshot(connection)).isEqualTo(before);
                try (var statement = connection.createStatement()) {
                    // Both pairs/scopes are valid individually, but existing identity cannot be rewritten.
                    assertThatThrownBy(() -> statement.execute("""
                            UPDATE source_endpoint SET endpoint_key='tfl-underground-status',
                                url='https://api.tfl.gov.uk/Line/Mode/tube/Status', calendar_scope=NULL,
                                poll_interval_seconds=NULL, next_poll_at=NULL
                            """)).isInstanceOf(java.sql.SQLException.class)
                            .hasMessageContaining("Endpoint key and URL cannot change");
                    assertThatThrownBy(() -> statement.execute("""
                            UPDATE source SET source_key='transport-for-london', scope='TFL_UNDERGROUND_STATUS'
                            """)).isInstanceOf(java.sql.SQLException.class)
                            .hasMessageContaining("Source scope cannot change");
                }
                assertThat(snapshot(connection)).isEqualTo(before);
            } finally {
                connection.setSchema(originalSchema);
            }
        }
    }

    private String snapshot(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("""
                SELECT json_build_object(
                    'source', (SELECT json_agg(row_to_json(s) ORDER BY id) FROM source s),
                    'endpoint', (SELECT json_agg(row_to_json(e) ORDER BY id) FROM source_endpoint e),
                    'run', (SELECT json_agg(row_to_json(r) ORDER BY id) FROM ingestion_run r),
                    'artifact', (SELECT json_agg(row_to_json(a) ORDER BY id) FROM evidence_artifact a))::text
                """)) {
            result.next();
            return result.getString(1);
        }
    }
}
