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
class NationalHighwaysPersistenceTest {
    @Autowired SourceRepository sources;
    @Autowired SourceEndpointRepository endpoints;
    @Autowired NationalHighwaysBootstrap bootstrap;
    @Autowired QualifiedSourceEndpoints qualified;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;

    private SourceEndpoint endpoint() {
        return endpoints.findByKey(SourceEndpoint.NATIONAL_HIGHWAYS_ROAD_CLOSURES_KEY).orElseThrow();
    }

    private String otherProvidersSnapshot() {
        return jdbc.queryForObject("""
                SELECT json_agg(row_to_json(configuration) ORDER BY endpoint_key)::text FROM (
                    SELECT e.endpoint_key, row_to_json(s) AS source, row_to_json(e) AS endpoint
                    FROM source s JOIN source_endpoint e ON e.source_id=s.id
                    WHERE e.endpoint_key IN ('gov-uk-bank-holidays-json', 'tfl-underground-status')
                ) configuration
                """, String.class);
    }

    @Test
    void startupAndRepeatedBootstrapCreateExactlyOnePendingCanonicalConfiguration() {
        String before = otherProvidersSnapshot();
        SourceEndpoint endpoint = endpoint();
        UUID id = endpoint.getId();
        UUID sourceId = endpoint.getSource().getId();
        bootstrap.initialize(); bootstrap.initialize();
        em.clear();
        SourceEndpoint loaded = endpoint();
        assertThat(loaded.getId()).isEqualTo(id);
        assertThat(loaded.getSource().getId()).isEqualTo(sourceId);
        assertThat(loaded.getSource().getKey()).isEqualTo(Source.NATIONAL_HIGHWAYS_KEY);
        assertThat(loaded.getSource().getDisplayName()).isEqualTo("National Highways");
        assertThat(loaded.getSource().getScope()).isEqualTo(Source.NATIONAL_HIGHWAYS_ROAD_CLOSURES_SCOPE);
        assertThat(loaded.getUrl()).isEqualTo(SourceEndpoint.NATIONAL_HIGHWAYS_ROAD_CLOSURES_URL);
        assertThat(loaded.hasCanonicalNationalHighwaysIdentity()).isTrue();
        assertThat(loaded.getQualificationStatus()).isEqualTo(QualificationStatus.PENDING);
        assertThat(loaded.getQualificationRecord()).isNull();
        assertThat(loaded.getUseRetentionPolicy()).isEqualTo(SourceEndpoint.PENDING_POLICY + "\n" + SourceEndpoint.NATIONAL_HIGHWAYS_USE_RETENTION_POLICY);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_endpoint WHERE source_id=?", Integer.class, sourceId)).isEqualTo(1);
        assertThat(sources.count()).isEqualTo(3);
        assertThat(endpoints.count()).isEqualTo(3);
        assertThat(otherProvidersSnapshot()).isEqualTo(before);
        assertThatIllegalStateException().isThrownBy(qualified::requireNationalHighwaysRoadClosures);
        assertThatIllegalStateException().isThrownBy(() -> qualified.requireQualified(id));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ingestion_run", Integer.class)).isZero();
    }

    @Test
    void ownerApprovalResolvesThroughNormalBoundaryAndSurvivesConflictingBootstrap() {
        String before = otherProvidersSnapshot();
        SourceEndpoint endpoint = endpoint();
        endpoint.qualify("Owner reviewed National Highways road closures contract, terms and Travel Live use",
                SourceEndpoint.NATIONAL_HIGHWAYS_USE_RETENTION_POLICY);
        em.flush();
        assertThat(qualified.requireNationalHighwaysRoadClosures().getId()).isEqualTo(endpoint.getId());
        assertThat(qualified.requireQualified(endpoint.getId()).getId()).isEqualTo(endpoint.getId());
        assertThatIllegalStateException().isThrownBy(qualified::requireTflUnderground);
        endpoint.setEnabled(false); endpoint.getSource().setEnabled(false);
        em.flush();
        long version = endpoint.getVersion();
        new NationalHighwaysBootstrap(jdbc, new NationalHighwaysBootstrapProperties(true, "Replacement", "Different policy")).initialize();
        bootstrap.initialize(); em.clear();
        SourceEndpoint loaded = endpoint();
        assertThat(loaded.getQualificationStatus()).isEqualTo(QualificationStatus.QUALIFIED);
        assertThat(loaded.getQualificationRecord()).startsWith("Owner reviewed");
        assertThat(loaded.getUseRetentionPolicy()).isEqualTo(SourceEndpoint.NATIONAL_HIGHWAYS_USE_RETENTION_POLICY);
        assertThat(loaded.isEnabled()).isFalse();
        assertThat(loaded.getSource().isEnabled()).isFalse();
        assertThat(loaded.getVersion()).isEqualTo(version);
        assertThat(otherProvidersSnapshot()).isEqualTo(before);
    }

    @Test
    void explicitQualifiedBootstrapOnlyAppliesToNewConfiguration() {
        UUID id = endpoint().getId();
        var approved = new NationalHighwaysBootstrap(jdbc, new NationalHighwaysBootstrapProperties(true, "Owner approval",
                SourceEndpoint.NATIONAL_HIGHWAYS_USE_RETENTION_POLICY));
        approved.initialize();
        em.clear();
        assertThat(endpoint().getQualificationStatus()).isEqualTo(QualificationStatus.PENDING);
        jdbc.update("DELETE FROM source_endpoint WHERE id=?", id);
        approved.initialize();
        em.clear();
        SourceEndpoint loaded = qualified.requireNationalHighwaysRoadClosures();
        assertThat(loaded.getQualificationRecord()).isEqualTo("Owner approval");
        assertThat(loaded.getUseRetentionPolicy()).isEqualTo(SourceEndpoint.NATIONAL_HIGHWAYS_USE_RETENTION_POLICY);
        assertThat(endpoints.count()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "endpoint"})
    void disabledConfigurationFailsClosed(String disabled) {
        SourceEndpoint endpoint = endpoint();
        endpoint.qualify("Fixture approval", SourceEndpoint.NATIONAL_HIGHWAYS_USE_RETENTION_POLICY);
        if (disabled.equals("source")) { endpoint.getSource().setEnabled(false); }
        else { endpoint.setEnabled(false); }
        em.flush();
        assertThatIllegalStateException().isThrownBy(qualified::requireNationalHighwaysRoadClosures);
        assertThatIllegalStateException().isThrownBy(() -> qualified.requireQualified(endpoint.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"gov-uk-bank-holidays", "transport-for-london"})
    void canonicalOwnershipCannotBeBypassedThroughGenericEligibility(String otherSource) {
        UUID foreign = sources.findByKey(otherSource).orElseThrow().getId();
        jdbc.update("UPDATE source_endpoint SET source_id=?, qualification_status='QUALIFIED', qualification_record='Fixture approval' WHERE endpoint_key=?",
                foreign, SourceEndpoint.NATIONAL_HIGHWAYS_ROAD_CLOSURES_KEY);
        em.clear();
        UUID id = endpoint().getId();
        assertThatIllegalStateException().isThrownBy(qualified::requireNationalHighwaysRoadClosures);
        assertThatIllegalStateException().isThrownBy(() -> qualified.requireQualified(id));
        assertThatIllegalStateException().isThrownBy(bootstrap::initialize)
                .withMessage("Existing National Highways road closures endpoint belongs to a different source");
    }

    @Test
    void missingCanonicalEndpointFailsSafely() {
        jdbc.update("DELETE FROM source_endpoint WHERE endpoint_key=?", SourceEndpoint.NATIONAL_HIGHWAYS_ROAD_CLOSURES_KEY);
        em.clear();
        assertThatIllegalArgumentException().isThrownBy(qualified::requireNationalHighwaysRoadClosures);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "qualification_status='QUALIFIED', qualification_record=NULL",
        "qualification_status='QUALIFIED', qualification_record=' '",
        "use_retention_policy=NULL", "use_retention_policy=' '",
        "url='https://api.data.nationalhighways.co.uk/roads/v2.0/closures?closureType=unplanned'",
        "url='https://api.data.nationalhighways.co.uk/roads/v2.0/closures?subscription-key=fixture'",
        "url='https://api.data.nationalhighways.co.uk/roads/v1.0/closures'",
        "url='https://api.data.nationalhighways.co.uk/roads/v2.0'",
        "url='https://api.tfl.gov.uk/Line/Mode/tube/Status'",
        "endpoint_key='national-highways-unplanned-closures'",
        "calendar_scope='CURRENT_YEAR'", "poll_interval_seconds=60", "next_poll_at=now()"
    })
    void databaseRejectsInvalidNationalHighwaysConfiguration(String assignment) {
        assertThatThrownBy(() -> jdbc.update("UPDATE source_endpoint SET " + assignment + " WHERE endpoint_key=?",
                SourceEndpoint.NATIONAL_HIGHWAYS_ROAD_CLOSURES_KEY)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"changed-scope", "other-key-with-roads-scope", "roads-key-with-bank-holidays-scope"})
    void databaseBindsTheRoadClosuresScopeToTheNationalHighwaysKeyOnly(String violation) {
        // Each case ends with exactly one rejected statement: PostgreSQL aborts the transaction afterwards.
        String insert = "INSERT INTO source (id,source_key,display_name,scope,enabled,version) VALUES (?,?,?,?,true,0)";
        assertThatThrownBy(() -> {
            switch (violation) {
                case "changed-scope" -> jdbc.update("UPDATE source SET scope='ARBITRARY' WHERE source_key=?", Source.NATIONAL_HIGHWAYS_KEY);
                case "other-key-with-roads-scope" -> jdbc.update(insert, UUID.randomUUID(), "other-roads", "Other roads",
                        Source.NATIONAL_HIGHWAYS_ROAD_CLOSURES_SCOPE);
                default -> {
                    jdbc.update("DELETE FROM source_endpoint WHERE endpoint_key=?", SourceEndpoint.NATIONAL_HIGHWAYS_ROAD_CLOSURES_KEY);
                    jdbc.update("DELETE FROM source WHERE source_key=?", Source.NATIONAL_HIGHWAYS_KEY);
                    jdbc.update(insert, UUID.randomUUID(), Source.NATIONAL_HIGHWAYS_KEY, "National Highways", Source.BANK_HOLIDAYS_SCOPE);
                }
            }
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void schemaHasNoCredentialStorage() {
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema=current_schema() AND table_name IN ('source', 'source_endpoint')
                  AND (column_name ILIKE '%key%' OR column_name ILIKE '%secret%' OR column_name ILIKE '%token%'
                       OR column_name ILIKE '%password%' OR column_name ILIKE '%credential%' OR column_name ILIKE '%header%')
                ORDER BY column_name
                """, String.class)).containsExactly("endpoint_key", "source_key");
    }
}
