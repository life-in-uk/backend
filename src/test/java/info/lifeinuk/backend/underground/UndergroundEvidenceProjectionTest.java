package info.lifeinuk.backend.underground;

import info.lifeinuk.backend.evidence.AcquisitionHistory;
import info.lifeinuk.backend.evidence.EvidenceArtifact;
import info.lifeinuk.backend.source.SourceEndpoint;
import info.lifeinuk.backend.support.IsolatedPostgres;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static info.lifeinuk.backend.underground.UndergroundCurrentStateProjector.Outcome.*;
import static info.lifeinuk.backend.underground.UndergroundEvidenceProjection.Result.Failure.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/** Persisted evidence → production lookup/provenance → accepted parser → accepted projector → Current State reader. */
@SpringBootTest(properties = {
    "spring.flyway.schemas=underground_evidence_projection_tests", "spring.flyway.default-schema=underground_evidence_projection_tests",
    "spring.jpa.properties.hibernate.default_schema=underground_evidence_projection_tests",
    "spring.datasource.hikari.schema=underground_evidence_projection_tests"
})
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
class UndergroundEvidenceProjectionTest {
    private static final String RICH = """
            [
              {"id": "waterloo-city", "name": "Waterloo & City — Café!", "modeName": "tube", "unknown": {"kept": false},
               "lineStatuses": [
                 {"statusSeverity": 9, "statusSeverityDescription": "Minor Delays", "reason": "  Signal fault — £5; “quoted”!  ",
                  "validityPeriods": [{"isNow": true}]},
                 {"statusSeverity": 10, "statusSeverityDescription": "Good Service", "reason": null},
                 {"statusSeverity": 6, "statusSeverityDescription": "Severe Delays", "reason": ""},
                 {"statusSeverity": 9, "statusSeverityDescription": "Minor Delays", "reason": "  Signal fault — £5; “quoted”!  "},
                 {"statusSeverity": -2147483648, "statusSeverityDescription": " Spaced  description "}
               ]},
              {"id": "bakerloo", "name": "Bakerloo", "modeName": "tube",
               "lineStatuses": [{"statusSeverity": 20, "statusSeverityDescription": "Service Closed", "reason": "Planned"}]},
              {"id": "central", "name": "Central", "modeName": "tube", "lineStatuses": []}
            ]
            """;
    private static final List<UndergroundLineStatus> RICH_LINES = List.of(
            new UndergroundLineStatus("waterloo-city", "Waterloo & City — Café!", List.of(
                    new UndergroundOperationalStatus(9, "Minor Delays", Optional.of("  Signal fault — £5; “quoted”!  ")),
                    new UndergroundOperationalStatus(10, "Good Service", Optional.empty()),
                    new UndergroundOperationalStatus(6, "Severe Delays", Optional.of("")),
                    new UndergroundOperationalStatus(9, "Minor Delays", Optional.of("  Signal fault — £5; “quoted”!  ")),
                    new UndergroundOperationalStatus(Integer.MIN_VALUE, " Spaced  description ", Optional.empty()))),
            new UndergroundLineStatus("bakerloo", "Bakerloo", List.of(
                    new UndergroundOperationalStatus(20, "Service Closed", Optional.of("Planned")))),
            new UndergroundLineStatus("central", "Central", List.of()));

    @Autowired UndergroundEvidenceProjection projection;
    @Autowired UndergroundCurrentStates states;
    @Autowired AcquisitionHistory history;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean TflUndergroundStatusParser parser;
    @MockitoSpyBean UndergroundCurrentStateProjector projector;

    @BeforeEach
    void prepare() {
        jdbc.execute("DROP TRIGGER IF EXISTS projection_test_reject ON underground_current_line");
        jdbc.execute("DROP FUNCTION IF EXISTS projection_test_reject()");
        jdbc.execute("TRUNCATE evidence_artifact, ingestion_run, underground_current_status, underground_current_line, underground_current_snapshot");
        jdbc.update("UPDATE source_endpoint SET source_id=(SELECT id FROM source WHERE source_key='transport-for-london') WHERE endpoint_key='tfl-underground-status'");
        jdbc.update("UPDATE source_endpoint SET source_id=(SELECT id FROM source WHERE source_key='gov-uk-bank-holidays') WHERE endpoint_key='gov-uk-bank-holidays-json'");
        jdbc.update("UPDATE source SET enabled=true");
        jdbc.update("UPDATE source_endpoint SET enabled=true, qualification_status='QUALIFIED', qualification_record='Offline fixture approval', use_retention_policy=? WHERE endpoint_key='tfl-underground-status'",
                SourceEndpoint.TFL_USE_RETENTION_POLICY);
        jdbc.update("UPDATE source_endpoint SET enabled=true, qualification_status='QUALIFIED', qualification_record='Offline fixture approval', use_retention_policy='Fixture policy' WHERE endpoint_key='gov-uk-bank-holidays-json'");
        reset(parser, projector);
    }

    private UUID endpoint(String key) {
        return jdbc.queryForObject("SELECT id FROM source_endpoint WHERE endpoint_key=?", UUID.class, key);
    }

    /** Application-path evidence: exactly as a successful acquisition would persist it. */
    private UUID successful(String endpointKey, String payload) {
        UUID run = history.start(endpoint(endpointKey));
        return history.succeed(run, payload.getBytes(StandardCharsets.UTF_8), "application/json", Instant.now());
    }
    private UUID successful(String payload) {
        return successful(SourceEndpoint.TFL_UNDERGROUND_KEY, payload);
    }

    /** Relational fixtures for provenance the application cannot create; database triggers still apply. */
    private UUID sqlEvidence(String endpointKey, String finalStatus, String payload) {
        UUID run = UUID.randomUUID();
        UUID artifact = UUID.randomUUID();
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        jdbc.update("INSERT INTO ingestion_run (id,source_endpoint_id,started_at,status,version) VALUES (?,?,now()-interval '1 minute','STARTED',0)",
                run, endpoint(endpointKey));
        jdbc.update("INSERT INTO evidence_artifact (id,ingestion_run_id,payload,media_type,observed_at,sha256) VALUES (?,?,?,'application/json',now()-interval '30 seconds',encode(sha256(?),'hex'))",
                artifact, run, bytes, bytes);
        switch (finalStatus) {
            case "SUCCESS" -> jdbc.update("UPDATE ingestion_run SET status='SUCCESS',completed_at=now() WHERE id=?", run);
            case "FAILED" -> jdbc.update("UPDATE ingestion_run SET status='FAILED',completed_at=now(),failure_code='FIXTURE',failure_message='Fixture failure' WHERE id=?", run);
            case "STARTED" -> { }
            default -> throw new IllegalArgumentException(finalStatus);
        }
        return artifact;
    }

    private Instant observedAt(UUID artifact) {
        return jdbc.queryForObject("SELECT observed_at FROM evidence_artifact WHERE id=?", java.sql.Timestamp.class, artifact).toInstant();
    }

    /** A valid older snapshot: any evidence that wrongly passed provenance or parsing would replace it. */
    private void seedOlderCurrentState() {
        projector.project(new UndergroundStatusInterpretation(UUID.randomUUID(), Instant.parse("2020-01-01T00:00:00Z"),
                List.of(new UndergroundLineStatus("seed", "Seed", List.of(new UndergroundOperationalStatus(10, "Good Service", Optional.empty()))))));
        reset(parser, projector);
    }

    private String currentState() {
        return jdbc.queryForObject("""
                SELECT json_build_object(
                    'snapshot',(SELECT json_agg(row_to_json(s)) FROM underground_current_snapshot s),
                    'lines',(SELECT json_agg(row_to_json(l) ORDER BY line_order) FROM underground_current_line l),
                    'statuses',(SELECT json_agg(row_to_json(t) ORDER BY line_id,status_order) FROM underground_current_status t))::text
                """, String.class);
    }

    private String evidenceHistory() {
        return jdbc.queryForObject("""
                SELECT json_agg(row_to_json(h) ORDER BY artifact_id)::text FROM (
                    SELECT a.id AS artifact_id,row_to_json(a) AS artifact,row_to_json(r) AS run,
                           row_to_json(e) AS endpoint,row_to_json(s) AS source
                    FROM evidence_artifact a JOIN ingestion_run r ON r.id=a.ingestion_run_id
                    JOIN source_endpoint e ON e.id=r.source_endpoint_id JOIN source s ON s.id=e.source_id) h
                """, String.class);
    }

    @Test
    void persistedEvidenceSurvivesTheCompleteProductionPathExactlyAndReplays() {
        UUID id = successful(RICH);
        String evidence = evidenceHistory();
        assertThat(projection.projectEvidence(id)).isEqualTo(new UndergroundEvidenceProjection.Result(APPLIED, null));
        verify(parser).parse(argThat((EvidenceArtifact artifact) -> artifact.getId().equals(id)));
        verify(projector).project(any());
        assertThat(states.current()).contains(new UndergroundCurrentState(id, observedAt(id), RICH_LINES));

        String projected = currentState();
        assertThat(projection.projectEvidence(id)).isEqualTo(new UndergroundEvidenceProjection.Result(REPLAYED, null));
        assertThat(currentState()).isEqualTo(projected);
        assertThat(evidenceHistory()).isEqualTo(evidence);
    }

    @Test
    void olderAndEqualTimeConflictingEvidenceKeepTheProjectorsAcceptedDecisions() {
        UUID first = history.start(endpoint(SourceEndpoint.TFL_UNDERGROUND_KEY));
        UUID second = history.start(endpoint(SourceEndpoint.TFL_UNDERGROUND_KEY));
        Instant shared = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID older = history.succeed(first, RICH.getBytes(StandardCharsets.UTF_8), "application/json", shared);
        UUID conflicting = history.succeed(second, "[]".getBytes(StandardCharsets.UTF_8), "application/json", shared);
        UUID newer = successful(RICH);

        assertThat(projection.projectEvidence(older).outcome()).isEqualTo(APPLIED);
        assertThat(projection.projectEvidence(conflicting).outcome()).isEqualTo(CONFLICT);
        assertThat(states.current().orElseThrow().evidenceArtifactId()).isEqualTo(older);
        assertThat(projection.projectEvidence(newer).outcome()).isEqualTo(APPLIED);
        String current = currentState();
        assertThat(projection.projectEvidence(older).outcome()).isEqualTo(IGNORED_OLDER);
        assertThat(currentState()).isEqualTo(current);
        assertThat(states.current()).contains(new UndergroundCurrentState(newer, observedAt(newer), RICH_LINES));
    }

    @Test
    void validEmptyObservationIsAnExplicitEmptySnapshotWithoutInventedFacts() {
        seedOlderCurrentState();
        UUID empty = successful("[]");
        assertThat(projection.projectEvidence(empty).outcome()).isEqualTo(APPLIED);
        assertThat(states.current()).contains(new UndergroundCurrentState(empty, observedAt(empty), List.of()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM underground_current_line", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM underground_current_status", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "missing", "bank-holidays-evidence", "tfl-endpoint-owned-by-other-source", "bank-holidays-endpoint-owned-by-tfl",
        "failed-run", "started-run", "endpoint-unqualified", "endpoint-disabled", "source-disabled"
    })
    void ineligibleProvenanceIsRejectedBeforeParsingWithoutTouchingStateOrEvidence(String provenance) {
        seedOlderCurrentState();
        UUID id = switch (provenance) {
            case "missing" -> UUID.randomUUID();
            // A valid Underground payload proves eligibility comes from provenance, never from parseability.
            case "bank-holidays-evidence" -> successful(SourceEndpoint.BANK_HOLIDAYS_KEY, RICH);
            case "tfl-endpoint-owned-by-other-source" -> {
                jdbc.update("UPDATE source_endpoint SET source_id=(SELECT id FROM source WHERE source_key='gov-uk-bank-holidays') WHERE endpoint_key='tfl-underground-status'");
                yield sqlEvidence(SourceEndpoint.TFL_UNDERGROUND_KEY, "SUCCESS", RICH);
            }
            case "bank-holidays-endpoint-owned-by-tfl" -> {
                jdbc.update("UPDATE source_endpoint SET source_id=(SELECT id FROM source WHERE source_key='transport-for-london') WHERE endpoint_key='gov-uk-bank-holidays-json'");
                yield sqlEvidence(SourceEndpoint.BANK_HOLIDAYS_KEY, "SUCCESS", RICH);
            }
            case "failed-run" -> sqlEvidence(SourceEndpoint.TFL_UNDERGROUND_KEY, "FAILED", RICH);
            case "started-run" -> sqlEvidence(SourceEndpoint.TFL_UNDERGROUND_KEY, "STARTED", RICH);
            case "endpoint-unqualified" -> {
                UUID artifact = successful(RICH);
                jdbc.update("UPDATE source_endpoint SET qualification_status='PENDING',qualification_record=NULL WHERE endpoint_key='tfl-underground-status'");
                yield artifact;
            }
            case "endpoint-disabled" -> {
                UUID artifact = successful(RICH);
                jdbc.update("UPDATE source_endpoint SET enabled=false WHERE endpoint_key='tfl-underground-status'");
                yield artifact;
            }
            case "source-disabled" -> {
                UUID artifact = successful(RICH);
                jdbc.update("UPDATE source SET enabled=false WHERE source_key='transport-for-london'");
                yield artifact;
            }
            default -> throw new IllegalArgumentException(provenance);
        };
        String state = currentState();
        String evidence = evidenceHistory();
        assertThat(projection.projectEvidence(id)).isEqualTo(new UndergroundEvidenceProjection.Result(null, EVIDENCE_NOT_ELIGIBLE));
        verify(parser, never()).parse(any());
        verify(projector, never()).project(any());
        assertThat(currentState()).isEqualTo(state);
        assertThat(evidenceHistory()).isEqualTo(evidence);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}", "not json", "[{\"id\":\"dlr\",\"name\":\"DLR\",\"modeName\":\"dlr\",\"lineStatuses\":[]}]",
        "[{\"id\":\"a\",\"name\":\"A\",\"modeName\":\"tube\",\"lineStatuses\":[]},{\"id\":\"a\",\"name\":\"A\",\"modeName\":\"tube\",\"lineStatuses\":[]}]"
    })
    void malformedEligibleEvidenceIsRetainedAndLeavesPreviousCurrentState(String payload) {
        seedOlderCurrentState();
        UUID id = successful(payload);
        String state = currentState();
        String evidence = evidenceHistory();
        assertThat(projection.projectEvidence(id)).isEqualTo(new UndergroundEvidenceProjection.Result(null, EVIDENCE_INVALID));
        verify(projector, never()).project(any());
        assertThat(currentState()).isEqualTo(state);
        assertThat(evidenceHistory()).isEqualTo(evidence);
        assertThat(jdbc.queryForObject("SELECT payload FROM evidence_artifact WHERE id=?", byte[].class, id))
                .containsExactly(payload.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void projectorFailureRollsBackWithoutPartialReplacementOrEvidenceLoss() {
        UUID previous = successful(RICH);
        assertThat(projection.projectEvidence(previous).outcome()).isEqualTo(APPLIED);
        UUID newer = successful("[{\"id\":\"victoria\",\"name\":\"Victoria\",\"modeName\":\"tube\",\"lineStatuses\":[]}]");
        String state = currentState();
        String evidence = evidenceHistory();
        jdbc.execute("CREATE FUNCTION projection_test_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected projection failure'; END $$");
        jdbc.execute("CREATE TRIGGER projection_test_reject BEFORE INSERT ON underground_current_line FOR EACH ROW EXECUTE FUNCTION projection_test_reject()");
        assertThat(projection.projectEvidence(newer)).isEqualTo(new UndergroundEvidenceProjection.Result(null, PROJECTION_FAILED));
        assertThat(currentState()).isEqualTo(state);
        assertThat(states.current().orElseThrow().evidenceArtifactId()).isEqualTo(previous);
        assertThat(evidenceHistory()).isEqualTo(evidence);
    }

    @Test
    void projectionRefusesAnAmbientTransaction() {
        UUID id = successful(RICH);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> projection.projectEvidence(id)))
                .isInstanceOf(IllegalTransactionStateException.class);
        verify(parser, never()).parse(any());
        assertThat(states.current()).isEmpty();
    }

    @Test
    void resultRequiresExactlyOneOfOutcomeOrFailure() {
        assertThatIllegalArgumentException().isThrownBy(() -> new UndergroundEvidenceProjection.Result(null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new UndergroundEvidenceProjection.Result(APPLIED, PROJECTION_FAILED));
    }
}
