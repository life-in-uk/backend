package info.lifeinuk.backend.source;

import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Canonical configuration only: insert once, preserve owner/operator decisions on restart. */
@Component
@EnableConfigurationProperties(NationalHighwaysBootstrapProperties.class)
public class NationalHighwaysBootstrap implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final NationalHighwaysBootstrapProperties properties;

    public NationalHighwaysBootstrap(JdbcTemplate jdbc, NationalHighwaysBootstrapProperties properties) {
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
                VALUES (?, ?, 'National Highways', ?, true, 0)
                ON CONFLICT (source_key) DO NOTHING
                """, UUID.randomUUID(), Source.NATIONAL_HIGHWAYS_KEY, Source.NATIONAL_HIGHWAYS_ROAD_CLOSURES_SCOPE);
        UUID sourceId = jdbc.queryForObject("SELECT id FROM source WHERE source_key=?", UUID.class,
                Source.NATIONAL_HIGHWAYS_KEY);
        jdbc.update("""
                INSERT INTO source_endpoint
                    (id, source_id, endpoint_key, url, qualification_status, qualification_record,
                     attribution_reference, use_retention_policy, enabled, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, true, 0)
                ON CONFLICT (endpoint_key) DO NOTHING
                """, UUID.randomUUID(), sourceId, SourceEndpoint.NATIONAL_HIGHWAYS_ROAD_CLOSURES_KEY,
                SourceEndpoint.NATIONAL_HIGHWAYS_ROAD_CLOSURES_URL, properties.qualified() ? "QUALIFIED" : "PENDING",
                properties.qualified() ? properties.qualificationRecord() : null,
                SourceEndpoint.NATIONAL_HIGHWAYS_REFERENCE,
                properties.qualified() ? properties.useRetentionPolicy()
                        : SourceEndpoint.PENDING_POLICY + "\n" + SourceEndpoint.NATIONAL_HIGHWAYS_USE_RETENTION_POLICY);
        UUID owner = jdbc.queryForObject("SELECT source_id FROM source_endpoint WHERE endpoint_key=?",
                UUID.class, SourceEndpoint.NATIONAL_HIGHWAYS_ROAD_CLOSURES_KEY);
        if (!sourceId.equals(owner)) {
            throw new IllegalStateException("Existing National Highways road closures endpoint belongs to a different source");
        }
    }
}
