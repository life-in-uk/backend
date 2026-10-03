package info.lifeinuk.backend.underground;

import info.lifeinuk.backend.LifeInUkBackendApplication;
import info.lifeinuk.backend.evidence.AcquisitionHistory;
import info.lifeinuk.backend.source.SourceEndpoint;
import info.lifeinuk.backend.support.IsolatedPostgres;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

/** Real application startups against an isolated schema; no provider transport is reachable from replay. */
@ExtendWith(OutputCaptureExtension.class)
class UndergroundEvidenceReplayTest {
    private static final String OPTION = "--" + UndergroundEvidenceReplay.OPTION + "=";
    private static final String PAYLOAD = """
            [{"id": "victoria", "name": "Victoria — Café!", "modeName": "tube", "lineStatuses": [
               {"statusSeverity": 9, "statusSeverityDescription": "Minor Delays", "reason": " Reason £ "},
               {"statusSeverity": 9, "statusSeverityDescription": "Minor Delays", "reason": " Reason £ "}]}]
            """;
    private static final List<UndergroundLineStatus> LINES = List.of(new UndergroundLineStatus("victoria", "Victoria — Café!", List.of(
            new UndergroundOperationalStatus(9, "Minor Delays", Optional.of(" Reason £ ")),
            new UndergroundOperationalStatus(9, "Minor Delays", Optional.of(" Reason £ ")))));
    private final String schema = "underground_replay_" + UUID.randomUUID().toString().replace("-", "");

    private ConfigurableApplicationContext start(String... args) {
        return new SpringApplicationBuilder(LifeInUkBackendApplication.class).web(WebApplicationType.NONE)
                .initializers(new IsolatedPostgres.Initializer(), context -> TestPropertyValues.of(
                        "spring.flyway.schemas=" + schema, "spring.flyway.default-schema=" + schema,
                        "spring.jpa.properties.hibernate.default_schema=" + schema,
                        "spring.datasource.hikari.schema=" + schema).applyTo(context.getEnvironment())).run(args);
    }

    private static String history(ConfigurableApplicationContext context) {
        return context.getBean(JdbcTemplate.class).queryForObject("""
                SELECT json_build_object(
                    'runs',(SELECT json_agg(row_to_json(r) ORDER BY r.id) FROM ingestion_run r),
                    'artifacts',(SELECT json_agg(row_to_json(a) ORDER BY a.id) FROM evidence_artifact a))::text
                """, String.class);
    }

    private static String currentState(ConfigurableApplicationContext context) {
        return context.getBean(JdbcTemplate.class).queryForObject("""
                SELECT json_build_object(
                    'snapshot',(SELECT json_agg(row_to_json(s)) FROM underground_current_snapshot s),
                    'lines',(SELECT json_agg(row_to_json(l) ORDER BY line_order) FROM underground_current_line l),
                    'statuses',(SELECT json_agg(row_to_json(t) ORDER BY line_id,status_order) FROM underground_current_status t))::text
                """, String.class);
    }

    @Test
    void explicitReplayProjectsOneProvenArtifactAndOrdinaryStartupDoesNothing(CapturedOutput output) {
        UUID valid;
        UUID bankHolidays;
        String history;
        try (var setup = start()) {
            var jdbc = setup.getBean(JdbcTemplate.class);
            jdbc.update("UPDATE source_endpoint SET qualification_status='QUALIFIED', qualification_record='Offline fixture approval', use_retention_policy=? WHERE endpoint_key='tfl-underground-status'",
                    SourceEndpoint.TFL_USE_RETENTION_POLICY);
            jdbc.update("UPDATE source_endpoint SET qualification_status='QUALIFIED', qualification_record='Offline fixture approval', use_retention_policy='Fixture policy' WHERE endpoint_key='gov-uk-bank-holidays-json'");
            var acquisitionHistory = setup.getBean(AcquisitionHistory.class);
            byte[] bytes = PAYLOAD.getBytes(StandardCharsets.UTF_8);
            UUID tflEndpoint = jdbc.queryForObject("SELECT id FROM source_endpoint WHERE endpoint_key='tfl-underground-status'", UUID.class);
            UUID bankEndpoint = jdbc.queryForObject("SELECT id FROM source_endpoint WHERE endpoint_key='gov-uk-bank-holidays-json'", UUID.class);
            valid = acquisitionHistory.succeed(acquisitionHistory.start(tflEndpoint), bytes, "application/json", Instant.now());
            bankHolidays = acquisitionHistory.succeed(acquisitionHistory.start(bankEndpoint), bytes, "application/json", Instant.now());
            history = history(setup);
        }

        try (var ordinary = start()) {
            assertThat(ordinary.getBean(UndergroundCurrentStates.class).current()).isEmpty();
            assertThat(history(ordinary)).isEqualTo(history);
        }

        String projected;
        try (var replay = start(OPTION + valid)) {
            var current = replay.getBean(UndergroundCurrentStates.class).current().orElseThrow();
            assertThat(current.evidenceArtifactId()).isEqualTo(valid);
            assertThat(current.lines()).isEqualTo(LINES);
            // Any acquisition would have recorded a run before transport; history is byte-for-byte unchanged.
            assertThat(history(replay)).isEqualTo(history);
            projected = currentState(replay);
        }
        assertThat(output).contains("Underground evidence " + valid + " projection outcome: APPLIED");

        try (var repeated = start(OPTION + valid)) {
            assertThat(currentState(repeated)).isEqualTo(projected);
            assertThat(history(repeated)).isEqualTo(history);
        }
        assertThat(output).contains("Underground evidence " + valid + " projection outcome: REPLAYED");

        for (UUID rejected : List.of(UUID.randomUUID(), bankHolidays)) {
            assertThatThrownBy(() -> start(OPTION + rejected)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Underground evidence " + rejected + " was not projected: EVIDENCE_NOT_ELIGIBLE");
        }
        try (var after = start()) {
            assertThat(currentState(after)).isEqualTo(projected);
            assertThat(history(after)).isEqualTo(history);
        }
    }

    @Test
    void malformedOptionsFailBeforeProjectionAndUnrelatedArgumentsAreIgnored() throws Exception {
        try (var context = start("--unrelated=" + UUID.randomUUID(), "positional")) {
            assertThat(context.getBean(UndergroundCurrentStates.class).current()).isEmpty();
            var replay = context.getBean(UndergroundEvidenceReplay.class);
            String before = currentState(context);
            for (String[] args : List.of(new String[] {OPTION + "not-a-uuid"}, new String[] {OPTION},
                    new String[] {"--" + UndergroundEvidenceReplay.OPTION},
                    new String[] {OPTION + "1-1-1-1-1"},
                    new String[] {OPTION + UUID.randomUUID(), OPTION + UUID.randomUUID()})) {
                assertThatIllegalArgumentException().isThrownBy(() -> replay.run(new DefaultApplicationArguments(args)));
            }
            replay.run(new DefaultApplicationArguments("--other=1", UUID.randomUUID().toString()));
            assertThat(currentState(context)).isEqualTo(before);
        }
    }

    @Test
    void replayDependencyGraphCannotReachAcquisitionOrProviderTransport() {
        try (var context = start()) {
            var beans = context.getBeanFactory();
            var seen = new HashSet<String>();
            var pending = new ArrayDeque<>(List.of("undergroundEvidenceReplay"));
            while (!pending.isEmpty()) {
                String name = pending.pop();
                if (seen.add(name)) {
                    pending.addAll(java.util.Arrays.stream(beans.getDependenciesForBean(name)).filter(beans::containsBean).toList());
                }
            }
            assertThat(seen).contains("undergroundEvidenceProjection", "undergroundEvidence",
                    "tflUndergroundStatusParser", "undergroundCurrentStateProjector");
            assertThat(seen).allSatisfy(name -> {
                Class<?> type = beans.getType(name);
                assertThat(type == null ? name : type.getName())
                        .doesNotStartWith("info.lifeinuk.backend.acquisition")
                        .doesNotContain("HttpClient");
            });
        }
    }
}
