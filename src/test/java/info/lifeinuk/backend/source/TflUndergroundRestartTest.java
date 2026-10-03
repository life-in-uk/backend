package info.lifeinuk.backend.source;

import info.lifeinuk.backend.LifeInUkBackendApplication;
import info.lifeinuk.backend.support.IsolatedPostgres;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class TflUndergroundRestartTest {
    @Test
    void realContextRestartPreservesIdentityOwnerPolicyAndDisabledState() {
        String schema = "tfl_restart_" + UUID.randomUUID().toString().replace("-", "");
        UUID[] ids;
        String ownerPolicy = SourceEndpoint.TFL_USE_RETENTION_POLICY + "\nOwner-specific provider review decision.";
        try (ConfigurableApplicationContext first = start(schema)) {
            var endpoints = first.getBean(SourceEndpointRepository.class);
            ids = new TransactionTemplate(first.getBean(PlatformTransactionManager.class)).execute(status -> {
                SourceEndpoint endpoint = endpoints.findByKey(SourceEndpoint.TFL_UNDERGROUND_KEY).orElseThrow();
                assertThat(endpoint.getQualificationStatus()).isEqualTo(QualificationStatus.PENDING);
                endpoint.qualify("Owner reviewed provider, dataset, endpoint, use and attribution", ownerPolicy);
                endpoint.setEnabled(false); endpoint.getSource().setEnabled(false);
                return new UUID[]{endpoint.getId(), endpoint.getSource().getId()};
            });
        }
        try (ConfigurableApplicationContext second = start(schema)) {
            var endpoints = second.getBean(SourceEndpointRepository.class);
            new TransactionTemplate(second.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> {
                SourceEndpoint endpoint = endpoints.findByKey(SourceEndpoint.TFL_UNDERGROUND_KEY).orElseThrow();
                assertThat(endpoint.getId()).isEqualTo(ids[0]);
                assertThat(endpoint.getSource().getId()).isEqualTo(ids[1]);
                assertThat(endpoint.getQualificationStatus()).isEqualTo(QualificationStatus.QUALIFIED);
                assertThat(endpoint.getQualificationRecord()).isEqualTo("Owner reviewed provider, dataset, endpoint, use and attribution");
                assertThat(endpoint.getUseRetentionPolicy()).isEqualTo(ownerPolicy);
                assertThat(endpoint.isEnabled()).isFalse();
                assertThat(endpoint.getSource().isEnabled()).isFalse();
                assertThat(endpoint.getCalendarScope()).isNull();
                assertThat(endpoint.getPollIntervalSeconds()).isNull();
                assertThat(endpoint.getNextPollAt()).isNull();
                assertThat(endpoints.count()).isEqualTo(2);
                assertThat(second.getBean(SourceRepository.class).count()).isEqualTo(2);
                assertThat(endpoints.findByKey(SourceEndpoint.BANK_HOLIDAYS_KEY).orElseThrow().getUseRetentionPolicy())
                        .isEqualTo(SourceEndpoint.PENDING_POLICY);
            });
        }
    }

    private ConfigurableApplicationContext start(String schema) {
        return new SpringApplicationBuilder(LifeInUkBackendApplication.class).web(WebApplicationType.NONE)
                .initializers(new IsolatedPostgres.Initializer(), context -> TestPropertyValues.of(
                        "spring.flyway.schemas=" + schema, "spring.flyway.default-schema=" + schema,
                        "spring.jpa.properties.hibernate.default_schema=" + schema,
                        "spring.datasource.hikari.schema=" + schema,
                        "life-in-uk.source.tfl-underground.qualified=false")
                        .applyTo(context.getEnvironment())).run();
    }
}
