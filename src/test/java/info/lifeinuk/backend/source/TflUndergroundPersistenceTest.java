package info.lifeinuk.backend.source;

import info.lifeinuk.backend.support.IsolatedPostgres;
import jakarta.persistence.EntityManager;
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
class TflUndergroundPersistenceTest {
    @Autowired SourceRepository sources;
    @Autowired SourceEndpointRepository endpoints;
    @Autowired TflUndergroundBootstrap bootstrap;
    @Autowired QualifiedSourceEndpoints qualified;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;

    private SourceEndpoint endpoint() {
        return endpoints.findByKey(SourceEndpoint.TFL_UNDERGROUND_KEY).orElseThrow();
    }

    private String bankHolidaysSnapshot() {
        return jdbc.queryForObject("""
                SELECT row_to_json(configuration)::text FROM (
                    SELECT row_to_json(s) AS source, row_to_json(e) AS endpoint
                    FROM source s JOIN source_endpoint e ON e.source_id=s.id
                    WHERE e.endpoint_key='gov-uk-bank-holidays-json'
                ) configuration
                """, String.class);
    }

    @Test
    void firstStartAndRepeatedBootstrapCreateExactlyOnePendingCanonicalConfiguration() {
        String before = bankHolidaysSnapshot();
        SourceEndpoint endpoint = endpoint();
        UUID id = endpoint.getId();
        UUID sourceId = endpoint.getSource().getId();
        bootstrap.initialize(); bootstrap.initialize();
        em.clear();
        SourceEndpoint loaded = endpoint();
        assertThat(loaded.getId()).isEqualTo(id);
        assertThat(loaded.getSource().getId()).isEqualTo(sourceId);
        assertThat(loaded.hasCanonicalTflIdentity()).isTrue();
        assertThat(loaded.getQualificationStatus()).isEqualTo(QualificationStatus.PENDING);
        assertThat(loaded.getQualificationRecord()).isNull();
        assertThat(sources.count()).isEqualTo(2);
        assertThat(endpoints.count()).isEqualTo(2);
        assertThat(bankHolidaysSnapshot()).isEqualTo(before);
        assertThatIllegalStateException().isThrownBy(qualified::requireTflUnderground);
        assertThatIllegalStateException().isThrownBy(() -> qualified.requireQualified(id));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ingestion_run", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM evidence_artifact", Integer.class)).isZero();
    }

    @Test
    void ownerApprovalResolvesThroughNormalBoundaryAndSurvivesConflictingBootstrap() {
        String before = bankHolidaysSnapshot();
        SourceEndpoint endpoint = endpoint();
        endpoint.qualify("Owner reviewed TfL Underground endpoint, Travel Live use and official terms",
                SourceEndpoint.TFL_USE_RETENTION_POLICY);
        em.flush();
        assertThat(qualified.requireTflUnderground().getId()).isEqualTo(endpoint.getId());
        assertThat(qualified.requireQualified(endpoint.getId()).getId()).isEqualTo(endpoint.getId());
        endpoint.setEnabled(false); endpoint.getSource().setEnabled(false);
        em.flush();
        long version = endpoint.getVersion();
        new TflUndergroundBootstrap(jdbc, new TflUndergroundBootstrapProperties(true, "Replacement", "Different policy")).initialize();
        bootstrap.initialize(); em.clear();
        SourceEndpoint loaded = endpoint();
        assertThat(loaded.getQualificationStatus()).isEqualTo(QualificationStatus.QUALIFIED);
        assertThat(loaded.getQualificationRecord()).startsWith("Owner reviewed");
        assertThat(loaded.getUseRetentionPolicy()).isEqualTo(SourceEndpoint.TFL_USE_RETENTION_POLICY);
        assertThat(loaded.isEnabled()).isFalse();
        assertThat(loaded.getSource().isEnabled()).isFalse();
        assertThat(loaded.getVersion()).isEqualTo(version);
        assertThat(bankHolidaysSnapshot()).isEqualTo(before);
    }

    @Test
    void explicitQualifiedBootstrapOnlyAppliesToNewConfiguration() {
        UUID id = endpoint().getId();
        new TflUndergroundBootstrap(jdbc, new TflUndergroundBootstrapProperties(true, "Owner approval", SourceEndpoint.TFL_USE_RETENTION_POLICY)).initialize();
        em.clear();
        assertThat(endpoint().getQualificationStatus()).isEqualTo(QualificationStatus.PENDING);
        jdbc.update("DELETE FROM source_endpoint WHERE id=?", id);
        new TflUndergroundBootstrap(jdbc, new TflUndergroundBootstrapProperties(true, "Owner approval", SourceEndpoint.TFL_USE_RETENTION_POLICY)).initialize();
        em.clear();
        SourceEndpoint loaded = qualified.requireTflUnderground();
        assertThat(loaded.getQualificationRecord()).isEqualTo("Owner approval");
        assertThat(loaded.getUseRetentionPolicy()).isEqualTo(SourceEndpoint.TFL_USE_RETENTION_POLICY);
        assertThat(endpoints.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "endpoint"})
    void disabledConfigurationFailsClosed(String disabled) {
        SourceEndpoint endpoint = endpoint();
        endpoint.qualify("Fixture approval", SourceEndpoint.TFL_USE_RETENTION_POLICY);
        if (disabled.equals("source")) { endpoint.getSource().setEnabled(false); }
        else { endpoint.setEnabled(false); }
        em.flush();
        assertThatIllegalStateException().isThrownBy(qualified::requireTflUnderground);
        assertThatIllegalStateException().isThrownBy(() -> qualified.requireQualified(endpoint.getId()));
    }

    @Test
    void canonicalOwnershipCannotBeBypassedThroughGenericEligibility() {
        UUID bankSource = sources.findByKey(Source.BANK_HOLIDAYS_KEY).orElseThrow().getId();
        jdbc.update("UPDATE source_endpoint SET source_id=?, qualification_status='QUALIFIED', qualification_record='Fixture approval' WHERE endpoint_key=?",
                bankSource, SourceEndpoint.TFL_UNDERGROUND_KEY);
        em.clear();
        UUID id = endpoint().getId();
        assertThatIllegalStateException().isThrownBy(qualified::requireTflUnderground);
        assertThatIllegalStateException().isThrownBy(() -> qualified.requireQualified(id));
        assertThatIllegalStateException().isThrownBy(bootstrap::initialize)
                .withMessage("Existing TfL Underground endpoint belongs to a different source");
    }

    @Test
    void missingCanonicalEndpointFailsSafely() {
        jdbc.update("DELETE FROM source_endpoint WHERE endpoint_key=?", SourceEndpoint.TFL_UNDERGROUND_KEY);
        em.clear();
        assertThatIllegalArgumentException().isThrownBy(qualified::requireTflUnderground);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "qualification_status='QUALIFIED', qualification_record=NULL",
        "qualification_status='QUALIFIED', qualification_record=' '",
        "use_retention_policy=NULL", "use_retention_policy=' '",
        "url='https://example.invalid/Status'", "url='https://api.tfl.gov.uk/Line/Mode/tube/Status?app_key=fixture'",
        "url='https://www.gov.uk/bank-holidays.json'", "endpoint_key='arbitrary'",
        "calendar_scope='CURRENT_YEAR'", "poll_interval_seconds=30", "next_poll_at=now()"
    })
    void databaseRejectsInvalidTflConfiguration(String assignment) {
        assertThatThrownBy(() -> jdbc.update("UPDATE source_endpoint SET " + assignment + " WHERE endpoint_key=?", SourceEndpoint.TFL_UNDERGROUND_KEY))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsUnknownAndMismatchedControlledScope() {
        assertThatThrownBy(() -> jdbc.update("UPDATE source SET scope='ARBITRARY' WHERE source_key=?", Source.TFL_KEY))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
