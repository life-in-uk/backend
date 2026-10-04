package info.lifeinuk.backend.source;

import info.lifeinuk.backend.support.IsolatedPostgres;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
@Transactional
class SourcePersistenceTest {
    @Autowired SourceRepository sources;
    @Autowired SourceEndpointRepository endpoints;
    @Autowired BankHolidaysBootstrap bootstrap;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    @Test
    void flywayCreatesAcceptedSchemaAndHibernateValidatesIt() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(9);
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name",
                String.class)).containsExactly("evidence_artifact", "flyway_schema_history", "guide", "guide_evidence", "guide_evidence_support", "guide_source", "ingestion_run", "roads_current_snapshot", "source", "source_endpoint",
                        "underground_current_line", "underground_current_snapshot", "underground_current_status");
        assertThat(sources.findByKey(Source.BANK_HOLIDAYS_KEY)).isPresent();
        assertThat(endpoints.findByKey(SourceEndpoint.BANK_HOLIDAYS_KEY)).isPresent();
    }

    @Test
    void initializationIsIdempotentAndPreservesExistingConfiguration() {
        SourceEndpoint endpoint = endpoints.findByKey(SourceEndpoint.BANK_HOLIDAYS_KEY).orElseThrow();
        UUID sourceId = endpoint.getSource().getId();
        UUID endpointId = endpoint.getId();
        endpoint.qualify("Reviewed explicitly", "Approved retention and attribution policy");
        endpoint.setEnabled(false);
        endpoint.getSource().setEnabled(false);
        endpoint.setNextPollAt(Instant.parse("2030-01-01T00:00:00Z"));
        entityManager.flush();
        long version = endpoint.getVersion();
        bootstrap.initialize();
        bootstrap.initialize();
        entityManager.clear();
        SourceEndpoint reloaded = endpoints.findByKey(SourceEndpoint.BANK_HOLIDAYS_KEY).orElseThrow();
        assertThat(sources.count()).isEqualTo(3);
        assertThat(endpoints.count()).isEqualTo(3);
        assertThat(reloaded.getId()).isEqualTo(endpointId);
        assertThat(reloaded.getSource().getId()).isEqualTo(sourceId);
        assertThat(reloaded.getSource().isEnabled()).isFalse();
        assertThat(reloaded.isEnabled()).isFalse();
        assertThat(reloaded.getQualificationRecord()).isEqualTo("Reviewed explicitly");
        assertThat(reloaded.getNextPollAt()).isEqualTo(Instant.parse("2030-01-01T00:00:00Z"));
        assertThat(reloaded.getVersion()).isEqualTo(version).isPositive();
    }

    @Test
    void explicitQualifiedBootstrapWorksButDoesNotUpgradeExistingPolicy() {
        endpoints.deleteAllInBatch();
        sources.deleteAllInBatch();
        BankHolidaysBootstrap approved = new BankHolidaysBootstrap(jdbc,
                new BankHolidaysBootstrapProperties(true, "Owner-approved smoke-test scope", "Attribution and bounded retention approved"));
        approved.initialize();
        entityManager.clear();
        SourceEndpoint endpoint = endpoints.findByKey(SourceEndpoint.BANK_HOLIDAYS_KEY).orElseThrow();
        assertThat(endpoint.isQualifiedAndEnabled()).isTrue();
        assertThat(endpoint.getQualificationRecord()).isEqualTo("Owner-approved smoke-test scope");
        assertThat(endpoint.getSource().getScope()).isEqualTo(Source.BANK_HOLIDAYS_SCOPE);
        assertThat(endpoint.getAttributionReference()).isEqualTo(SourceEndpoint.ATTRIBUTION_REFERENCE);
        assertThat(endpoint.getNextPollAt()).isNotNull();
        bootstrap.initialize();
        assertThat(endpoints.count()).isEqualTo(1);
        assertThat(sources.count()).isEqualTo(1);
    }

    @Test
    void defaultBootstrapIsPendingNotPermission() {
        SourceEndpoint endpoint = endpoints.findByKey(SourceEndpoint.BANK_HOLIDAYS_KEY).orElseThrow();
        assertThat(endpoint.getQualificationStatus()).isEqualTo(QualificationStatus.PENDING);
        assertThat(endpoint.getQualificationRecord()).isNull();
        assertThat(endpoint.isQualifiedAndEnabled()).isFalse();
        new BankHolidaysBootstrap(jdbc, new BankHolidaysBootstrapProperties(true, "Later config", "Later policy")).initialize();
        entityManager.clear();
        assertThat(endpoints.findByKey(SourceEndpoint.BANK_HOLIDAYS_KEY).orElseThrow().isQualifiedAndEnabled()).isFalse();
    }

    @Test
    void databaseRejectsDuplicateSourceIdentity() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO source SELECT ?, source_key, display_name, scope, enabled, version FROM source",
                UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsDuplicateEndpointIdentity() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO source_endpoint SELECT ?, source_id, endpoint_key, url, qualification_status, qualification_record, attribution_reference, use_retention_policy, enabled, calendar_scope, poll_interval_seconds, next_poll_at, version FROM source_endpoint",
                UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRequiresExistingSource() {
        assertThatThrownBy(() -> jdbc.update("UPDATE source_endpoint SET source_id = ? WHERE endpoint_key = 'gov-uk-bank-holidays-json'", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRestrictsOfficialUrl() {
        assertThatThrownBy(() -> jdbc.update("UPDATE source_endpoint SET url = 'https://example.com/arbitrary' WHERE endpoint_key = 'gov-uk-bank-holidays-json'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRequiresQualificationRecord() {
        assertThatThrownBy(() -> jdbc.update("UPDATE source_endpoint SET qualification_status = 'QUALIFIED' WHERE endpoint_key = 'gov-uk-bank-holidays-json'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRequiresPolicy() {
        assertThatThrownBy(() -> jdbc.update("UPDATE source_endpoint SET use_retention_policy = ' ' WHERE endpoint_key = 'gov-uk-bank-holidays-json'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRequiresPollingEligibility() {
        assertThatThrownBy(() -> jdbc.update("UPDATE source_endpoint SET next_poll_at = NULL WHERE endpoint_key = 'gov-uk-bank-holidays-json'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRestrictsCalendarScope() {
        assertThatThrownBy(() -> jdbc.update("UPDATE source_endpoint SET calendar_scope = 'ALL_YEARS' WHERE endpoint_key = 'gov-uk-bank-holidays-json'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRestrictsDailyPolling() {
        assertThatThrownBy(() -> jdbc.update("UPDATE source_endpoint SET poll_interval_seconds = 0 WHERE endpoint_key = 'gov-uk-bank-holidays-json'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE source SET source_key = '' WHERE source_key = 'gov-uk-bank-holidays'",
            "UPDATE source SET display_name = ' ' WHERE source_key = 'gov-uk-bank-holidays'",
            "UPDATE source SET version = -1 WHERE source_key = 'gov-uk-bank-holidays'",
            "UPDATE source_endpoint SET endpoint_key = 'arbitrary-endpoint' WHERE endpoint_key = 'gov-uk-bank-holidays-json'",
            "UPDATE source_endpoint SET qualification_status = 'UNKNOWN' WHERE endpoint_key = 'gov-uk-bank-holidays-json'",
            "UPDATE source_endpoint SET qualification_status = 'QUALIFIED', qualification_record = ' ' WHERE endpoint_key = 'gov-uk-bank-holidays-json'",
            "UPDATE source_endpoint SET attribution_reference = ' ' WHERE endpoint_key = 'gov-uk-bank-holidays-json'",
            "UPDATE source_endpoint SET enabled = NULL WHERE endpoint_key = 'gov-uk-bank-holidays-json'"
    })
    void databaseRejectsInvalidRequiredConfiguration(String statement) {
        assertThatThrownBy(() -> jdbc.update(statement)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void bootstrapPreservesUnrelatedSourceAndRejectsMismatchedEndpointOwnership() {
        Source other = sources.saveAndFlush(new Source("other-source", "Unrelated configuration"));
        jdbc.update("UPDATE source_endpoint SET source_id = ? WHERE endpoint_key = 'gov-uk-bank-holidays-json'", other.getId());
        assertThatIllegalStateException().isThrownBy(bootstrap::initialize)
                .withMessage("Existing Bank Holidays endpoint belongs to a different source");
        assertThat(jdbc.queryForObject("SELECT display_name FROM source WHERE id = ?", String.class, other.getId()))
                .isEqualTo("Unrelated configuration");
        assertThat(jdbc.queryForObject("SELECT source_id FROM source_endpoint WHERE endpoint_key='gov-uk-bank-holidays-json'", UUID.class)).isEqualTo(other.getId());
    }
}
