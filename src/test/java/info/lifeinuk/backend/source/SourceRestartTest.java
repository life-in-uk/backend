package info.lifeinuk.backend.source;

import info.lifeinuk.backend.LifeInUkBackendApplication;
import info.lifeinuk.backend.support.IsolatedPostgres;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.assertThat;

class SourceRestartTest {
    @Test
    void configurationSurvivesARealApplicationContextRestart() {
        String schema = "restart_" + UUID.randomUUID().toString().replace("-", "");
        UUID endpointId;
        UUID sourceId;
        Instant nextPoll = Instant.parse("2031-04-05T12:00:00Z");
        try (ConfigurableApplicationContext first = start(schema)) {
            SourceEndpointRepository endpoints = first.getBean(SourceEndpointRepository.class);
            TransactionTemplate tx = new TransactionTemplate(first.getBean(PlatformTransactionManager.class));
            UUID[] identities = tx.execute(status -> {
                SourceEndpoint endpoint = endpoints.findByKey(SourceEndpoint.BANK_HOLIDAYS_KEY).orElseThrow();
                endpoint.qualify("Explicit owner decision", "Approved smoke-test retention with GOV.UK attribution");
                endpoint.setEnabled(false);
                endpoint.getSource().setEnabled(false);
                endpoint.setNextPollAt(nextPoll);
                return new UUID[] {endpoint.getId(), endpoint.getSource().getId()};
            });
            endpointId = identities[0];
            sourceId = identities[1];
        }
        try (ConfigurableApplicationContext second = start(schema)) {
            SourceEndpointRepository endpoints = second.getBean(SourceEndpointRepository.class);
            TransactionTemplate tx = new TransactionTemplate(second.getBean(PlatformTransactionManager.class));
            tx.executeWithoutResult(status -> {
                SourceEndpoint endpoint = endpoints.findByKey(SourceEndpoint.BANK_HOLIDAYS_KEY).orElseThrow();
                assertThat(endpoint.getId()).isEqualTo(endpointId);
                assertThat(endpoint.getSource().getId()).isEqualTo(sourceId);
                assertThat(endpoint.getQualificationStatus()).isEqualTo(QualificationStatus.QUALIFIED);
                assertThat(endpoint.getQualificationRecord()).isEqualTo("Explicit owner decision");
                assertThat(endpoint.isEnabled()).isFalse();
                assertThat(endpoint.getSource().isEnabled()).isFalse();
                assertThat(endpoint.getNextPollAt()).isEqualTo(nextPoll);
                assertThat(endpoint.getVersion()).isPositive();
                assertThat(endpoints.count()).isEqualTo(3);
                assertThat(second.getBean(SourceRepository.class).count()).isEqualTo(3);
            });
        }
    }

    private ConfigurableApplicationContext start(String schema) {
        return new SpringApplicationBuilder(LifeInUkBackendApplication.class)
                .web(WebApplicationType.NONE)
                .initializers(new IsolatedPostgres.Initializer(), context -> TestPropertyValues.of(
                        "spring.flyway.schemas=" + schema,
                        "spring.flyway.default-schema=" + schema,
                        "spring.jpa.properties.hibernate.default_schema=" + schema,
                        "spring.datasource.hikari.schema=" + schema)
                        .applyTo(context.getEnvironment()))
                .run();
    }
}
