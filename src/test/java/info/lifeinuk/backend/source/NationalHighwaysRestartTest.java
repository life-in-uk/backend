package info.lifeinuk.backend.source;

import info.lifeinuk.backend.LifeInUkBackendApplication;
import info.lifeinuk.backend.acquisition.NationalHighwaysRoadsProperties;
import info.lifeinuk.backend.support.IsolatedPostgres;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class NationalHighwaysRestartTest {
    /** Fixture only: never a real National Highways subscription key. */
    private static final String SENTINEL_KEY = "offline-sentinel-subscription-key-7f3c";

    @Test
    void restartPreservesOwnerDecisionsAndTheConfiguredSecretIsNeverPersisted() {
        String schema = "nh_restart_" + UUID.randomUUID().toString().replace("-", "");
        UUID[] ids;
        String ownerPolicy = SourceEndpoint.NATIONAL_HIGHWAYS_USE_RETENTION_POLICY + "\nOwner-specific licence review decision.";
        try (ConfigurableApplicationContext first = start(schema, SENTINEL_KEY)) {
            var properties = first.getBean(NationalHighwaysRoadsProperties.class);
            assertThat(properties.hasApiKey()).isTrue();
            assertThat(properties.toString()).doesNotContain(SENTINEL_KEY).contains("<redacted>");
            var endpoints = first.getBean(SourceEndpointRepository.class);
            ids = new TransactionTemplate(first.getBean(PlatformTransactionManager.class)).execute(status -> {
                SourceEndpoint endpoint = endpoints.findByKey(SourceEndpoint.NATIONAL_HIGHWAYS_ROAD_CLOSURES_KEY).orElseThrow();
                assertThat(endpoint.getQualificationStatus()).isEqualTo(QualificationStatus.PENDING);
                endpoint.qualify("Owner reviewed National Highways contract, terms, use and attribution", ownerPolicy);
                endpoint.setEnabled(false); endpoint.getSource().setEnabled(false);
                return new UUID[]{endpoint.getId(), endpoint.getSource().getId()};
            });
            assertThat(everyRowInSchema(first.getBean(JdbcTemplate.class))).doesNotContain(SENTINEL_KEY);
        }
        try (ConfigurableApplicationContext second = start(schema, "")) {
            assertThat(second.getBean(NationalHighwaysRoadsProperties.class).hasApiKey()).isFalse();
            var endpoints = second.getBean(SourceEndpointRepository.class);
            new TransactionTemplate(second.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> {
                SourceEndpoint endpoint = endpoints.findByKey(SourceEndpoint.NATIONAL_HIGHWAYS_ROAD_CLOSURES_KEY).orElseThrow();
                assertThat(endpoint.getId()).isEqualTo(ids[0]);
                assertThat(endpoint.getSource().getId()).isEqualTo(ids[1]);
                assertThat(endpoint.getQualificationStatus()).isEqualTo(QualificationStatus.QUALIFIED);
                assertThat(endpoint.getUseRetentionPolicy()).isEqualTo(ownerPolicy);
                assertThat(endpoint.isEnabled()).isFalse();
                assertThat(endpoint.getSource().isEnabled()).isFalse();
                assertThat(endpoints.count()).isEqualTo(3);
            });
            assertThat(everyRowInSchema(second.getBean(JdbcTemplate.class))).doesNotContain(SENTINEL_KEY);
        }
    }

    private static String everyRowInSchema(JdbcTemplate jdbc) {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema=current_schema() ORDER BY table_name", String.class);
        assertThat(tables).contains("source", "source_endpoint", "flyway_schema_history");
        var all = new StringBuilder();
        for (String table : tables) {
            jdbc.queryForList("SELECT row_to_json(t)::text FROM \"" + table + "\" t", String.class).forEach(all::append);
        }
        return all.toString();
    }

    private ConfigurableApplicationContext start(String schema, String apiKey) {
        return new SpringApplicationBuilder(LifeInUkBackendApplication.class).web(WebApplicationType.NONE)
                .initializers(new IsolatedPostgres.Initializer(), context -> TestPropertyValues.of(
                        "spring.flyway.schemas=" + schema, "spring.flyway.default-schema=" + schema,
                        "spring.jpa.properties.hibernate.default_schema=" + schema,
                        "spring.datasource.hikari.schema=" + schema,
                        "life-in-uk.acquisition.national-highways.api-key=" + apiKey)
                        .applyTo(context.getEnvironment())).run();
    }
}
