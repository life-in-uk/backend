package info.lifeinuk.backend.source;

import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Insert-only source-owned bootstrap; existing policy is never silently changed. */
@Component
@EnableConfigurationProperties(BankHolidaysBootstrapProperties.class)
public class BankHolidaysBootstrap implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final BankHolidaysBootstrapProperties properties;

    public BankHolidaysBootstrap(JdbcTemplate jdbc, BankHolidaysBootstrapProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        initialize();
    }

    @Transactional
    public void initialize() {
        jdbc.update("""
                INSERT INTO source (id, source_key, display_name, scope, enabled, version)
                VALUES (?, ?, 'GOV.UK Bank Holidays', ?, true, 0)
                ON CONFLICT (source_key) DO NOTHING
                """, UUID.randomUUID(), Source.BANK_HOLIDAYS_KEY, Source.BANK_HOLIDAYS_SCOPE);
        UUID sourceId = jdbc.queryForObject("SELECT id FROM source WHERE source_key = ?",
                UUID.class, Source.BANK_HOLIDAYS_KEY);
        jdbc.update("""
                INSERT INTO source_endpoint
                    (id, source_id, endpoint_key, url, qualification_status, qualification_record,
                     attribution_reference, use_retention_policy, enabled, calendar_scope,
                     poll_interval_seconds, next_poll_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, true, 'CURRENT_YEAR', ?, ?, 0)
                ON CONFLICT (endpoint_key) DO NOTHING
                """, UUID.randomUUID(), sourceId, SourceEndpoint.BANK_HOLIDAYS_KEY,
                SourceEndpoint.BANK_HOLIDAYS_URL,
                properties.qualified() ? "QUALIFIED" : "PENDING",
                properties.qualified() ? properties.qualificationRecord() : null,
                SourceEndpoint.ATTRIBUTION_REFERENCE,
                properties.qualified() ? properties.useRetentionPolicy() : SourceEndpoint.PENDING_POLICY,
                SourceEndpoint.DAILY_POLL_SECONDS, java.sql.Timestamp.from(Instant.now()));
        UUID endpointSource = jdbc.queryForObject("SELECT source_id FROM source_endpoint WHERE endpoint_key = ?",
                UUID.class, SourceEndpoint.BANK_HOLIDAYS_KEY);
        if (!sourceId.equals(endpointSource)) {
            throw new IllegalStateException("Existing Bank Holidays endpoint belongs to a different source");
        }
    }
}
