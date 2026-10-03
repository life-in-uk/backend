package info.lifeinuk.backend.underground;

import info.lifeinuk.backend.support.IsolatedPostgres;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import static info.lifeinuk.backend.underground.UndergroundCurrentStateProjector.Outcome.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.flyway.schemas=underground_current_tests", "spring.flyway.default-schema=underground_current_tests",
    "spring.jpa.properties.hibernate.default_schema=underground_current_tests", "spring.datasource.hikari.schema=underground_current_tests"
})
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
class UndergroundCurrentStateTest {
    @Autowired UndergroundCurrentStateProjector projector;
    @Autowired UndergroundCurrentStates states;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean UndergroundCurrentStateStore store;

    @BeforeEach
    void clear() {
        org.mockito.Mockito.reset(store);
        jdbc.execute("DROP TRIGGER IF EXISTS current_test_hook ON underground_current_line");
        jdbc.execute("DROP FUNCTION IF EXISTS current_test_hook()");
        jdbc.execute("TRUNCATE underground_current_status, underground_current_line, underground_current_snapshot");
    }

    private UndergroundLineStatus line(String id) {
        return new UndergroundLineStatus(id, "  Source " + id + " £ —  ", List.of(
                new UndergroundOperationalStatus(9, " Café!  ", Optional.of("  Unicode £ — reason!  ")),
                new UndergroundOperationalStatus(10, "Next", Optional.empty()),
                new UndergroundOperationalStatus(7, "Third", Optional.of("")),
                new UndergroundOperationalStatus(9, " Café!  ", Optional.of("  Unicode £ — reason!  "))));
    }
    private UndergroundStatusInterpretation input(long second, String... ids) {
        return new UndergroundStatusInterpretation(UUID.randomUUID(), Instant.ofEpochSecond(second, 123456789),
                java.util.Arrays.stream(ids).map(this::line).toList());
    }
    private UndergroundCurrentState expected(UndergroundStatusInterpretation input) {
        return new UndergroundCurrentState(input.evidenceArtifactId(), input.observedAt(), input.lines());
    }
    private String snapshot() {
        return jdbc.queryForObject("""
                SELECT json_build_object(
                    'snapshot',(SELECT json_agg(row_to_json(s)) FROM underground_current_snapshot s),
                    'lines',(SELECT json_agg(row_to_json(l) ORDER BY line_order) FROM underground_current_line l),
                    'statuses',(SELECT json_agg(row_to_json(t) ORDER BY line_id,status_order) FROM underground_current_status t))::text
                """, String.class);
    }

    @Test
    void firstSnapshotPersistsAllOrderedExactFactsAndProvenanceWithoutMutatingInput() {
        assertThat(states.current()).isEmpty();
        var input = input(1, "victoria", "central", "jubilee");
        var copy = expected(input);
        assertThat(projector.project(input)).isEqualTo(APPLIED);
        assertThat(states.current()).contains(copy);
        assertThat(input.lines()).isEqualTo(copy.lines());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM underground_current_line", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM underground_current_status", Integer.class)).isEqualTo(12);
        assertThat(states.current().orElseThrow().lines().getFirst().statuses())
                .extracting(UndergroundOperationalStatus::severity).containsExactly(9, 10, 7, 9);
    }

    @Test
    void newerSnapshotReplacesEverythingAndRemovesMissingLines() {
        projector.project(input(1, "central", "jubilee", "victoria"));
        var newer = new UndergroundStatusInterpretation(UUID.randomUUID(), Instant.ofEpochSecond(2, 123456789),
                List.of(new UndergroundLineStatus("jubilee", " Changed source name ", List.of(
                        new UndergroundOperationalStatus(3, " Changed source status ", Optional.of(" New reason ")))),
                        line("bakerloo")));
        assertThat(projector.project(newer)).isEqualTo(APPLIED);
        assertThat(states.current()).contains(expected(newer));
        assertThat(jdbc.queryForList("SELECT line_id FROM underground_current_line ORDER BY line_order", String.class))
                .containsExactly("jubilee", "bakerloo");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM underground_current_status", Integer.class)).isEqualTo(5);
    }

    @Test
    void olderReplayAndEqualTimeConflictLeaveActualDatabaseUntouched() {
        var current = input(2, "central");
        projector.project(current);
        String before = snapshot();
        assertThat(projector.project(input(1, "old"))).isEqualTo(IGNORED_OLDER);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(projector.project(current)).isEqualTo(REPLAYED);
        assertThat(snapshot()).isEqualTo(before);
        var conflict = new UndergroundStatusInterpretation(UUID.randomUUID(), current.observedAt(), List.of(line("other")));
        assertThat(projector.project(conflict)).isEqualTo(CONFLICT);
        assertThat(snapshot()).isEqualTo(before);
        var changedFacts = new UndergroundStatusInterpretation(current.evidenceArtifactId(), current.observedAt(), conflict.lines());
        assertThat(projector.project(changedFacts)).isEqualTo(CONFLICT);
        var changedTime = new UndergroundStatusInterpretation(current.evidenceArtifactId(), current.observedAt().plusSeconds(1), current.lines());
        assertThat(projector.project(changedTime)).isEqualTo(CONFLICT);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void nanosecondObservationOrderingAndReplayAreExact() {
        var old = input(1, "old");
        var newer = new UndergroundStatusInterpretation(UUID.randomUUID(), old.observedAt().plusNanos(1), List.of(line("new")));
        projector.project(old);
        assertThat(projector.project(newer)).isEqualTo(APPLIED);
        assertThat(projector.project(old)).isEqualTo(IGNORED_OLDER);
        assertThat(projector.project(newer)).isEqualTo(REPLAYED);
        assertThat(states.current()).contains(expected(newer));
    }

    @Test
    void emptySnapshotIsExplicitAndRemovesAllPreviousRows() {
        var firstEmpty = input(1);
        assertThat(projector.project(firstEmpty)).isEqualTo(APPLIED);
        assertThat(states.current()).contains(expected(firstEmpty));
        projector.project(input(2, "central"));
        var newerEmpty = input(3);
        assertThat(projector.project(newerEmpty)).isEqualTo(APPLIED);
        assertThat(projector.project(newerEmpty)).isEqualTo(REPLAYED);
        assertThat(states.current()).contains(expected(newerEmpty));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM underground_current_status", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM underground_current_line", Integer.class)).isZero();
    }

    @Test
    void lineWithNoStatusesRemainsPresentWithoutInventedStatus() {
        var input = new UndergroundStatusInterpretation(UUID.randomUUID(), Instant.EPOCH,
                List.of(new UndergroundLineStatus("central", "Central", List.of())));
        projector.project(input);
        assertThat(states.current()).contains(expected(input));
    }

    @Test
    void duplicateLineIdentityFailsWithoutDamagingCurrentSnapshot() {
        projector.project(input(1, "existing"));
        String before = snapshot();
        assertThatIllegalArgumentException().isThrownBy(() -> projector.project(input(2, "duplicate", "duplicate")));
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void failedReplacementRollsBackMetadataLinesAndStatuses(boolean first) {
        if (!first) { projector.project(input(1, "existing")); }
        String before = snapshot();
        jdbc.execute("CREATE FUNCTION current_test_hook() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.line_id='fail' THEN RAISE EXCEPTION 'Injected midway failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER current_test_hook BEFORE INSERT ON underground_current_line FOR EACH ROW EXECUTE FUNCTION current_test_hook()");
        assertThatThrownBy(() -> projector.project(input(2, "inserted-before-failure", "fail")))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(snapshot()).isEqualTo(before);
    }

    private void awaitBlocked(String sql) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (jdbc.queryForObject(sql, Integer.class) == 0 && System.nanoTime() < deadline) { Thread.sleep(10); }
        assertThat(jdbc.queryForObject(sql, Integer.class)).isGreaterThan(0);
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void competingTransactionsCannotCommitStaleSnapshotAndReadersSeeNoPartialReplacement(boolean newerHoldsLock, boolean first) throws Exception {
        var baseline = input(0, "baseline");
        if (!first) { projector.project(baseline); }
        var newer = input(2, "new-before-pause", "pause");
        var older = input(1, "old-before-pause", "pause");
        var holder = newerHoldsLock ? newer : older;
        var waiter = newerHoldsLock ? older : newer;
        jdbc.execute("CREATE FUNCTION current_test_hook() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.line_id='pause' THEN PERFORM pg_advisory_xact_lock(180018); END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER current_test_hook BEFORE INSERT ON underground_current_line FOR EACH ROW EXECUTE FUNCTION current_test_hook()");
        try (var connection = jdbc.getDataSource().getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) { statement.execute("SELECT pg_advisory_xact_lock(180018)"); }
            try {
                var holding = executor.submit(() -> projector.project(holder));
                awaitBlocked("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND objid=180018 AND NOT granted");
                // Writer has already deleted/reinserted part of its snapshot, but committed readers see only old state.
                assertThat(states.current()).isEqualTo(first ? Optional.empty() : Optional.of(expected(baseline)));
                var waiting = executor.submit(() -> projector.project(waiter));
                awaitBlocked("SELECT count(*) FROM pg_stat_activity WHERE cardinality(pg_blocking_pids(pid))>0 AND query LIKE '%underground_current_snapshot%'");
                assertThat(waiting.isDone()).isFalse();
                connection.commit();
                assertThat(holding.get(5, TimeUnit.SECONDS)).isEqualTo(APPLIED);
                assertThat(waiting.get(5, TimeUnit.SECONDS)).isEqualTo(newerHoldsLock ? IGNORED_OLDER : APPLIED);
            } finally { connection.rollback(); }
        }
        assertThat(states.current()).contains(expected(newer));
    }

    @Test
    void readSpanningAWriterCommitStillReturnsOneWholeSnapshot() throws Exception {
        var old = input(1, "old");
        var newer = input(2, "new");
        projector.project(old);
        var headerRead = new java.util.concurrent.CountDownLatch(1);
        var continueRead = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            headerRead.countDown();
            if (!continueRead.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("Reader release timed out"); }
            return invocation.callRealMethod();
        }).when(store).withLines(org.mockito.ArgumentMatchers.any());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var reader = executor.submit(states::current);
            try {
                assertThat(headerRead.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(projector.project(newer)).isEqualTo(APPLIED);
            } finally { continueRead.countDown(); }
            assertThat(reader.get(5, TimeUnit.SECONDS)).contains(expected(old));
        }
        assertThat(states.current()).contains(expected(newer));
    }

    @Test
    void databaseConstraintsProtectRequiredFactsOrderIdentityAndProvenance() {
        projector.project(input(1, "central"));
        for (String sql : List.of(
                "UPDATE underground_current_snapshot SET evidence_artifact_id=NULL",
                "UPDATE underground_current_snapshot SET observed_epoch_second=NULL",
                "UPDATE underground_current_snapshot SET observed_nano=1000000000",
                "INSERT INTO underground_current_snapshot VALUES (2,'00000000-0000-0000-0000-000000000000',0,0)",
                "UPDATE underground_current_line SET line_name=' '",
                "UPDATE underground_current_line SET line_order=-1",
                "INSERT INTO underground_current_line VALUES ('another',1,'Another',0)",
                "UPDATE underground_current_status SET description=' '",
                "UPDATE underground_current_status SET status_order=-1",
                "INSERT INTO underground_current_status VALUES ('central',0,9,'Duplicate order',NULL)")) {
            assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }

    @Test
    void provenanceHasNoForeignKeyToRawEvidenceAndNeedsNoLiveEvidenceRow() {
        var input = input(1, "central");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM evidence_artifact WHERE id=?", Integer.class, input.evidenceArtifactId())).isZero();
        projector.project(input);
        assertThat(states.current()).contains(expected(input));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint c
                JOIN pg_class child ON child.oid=c.conrelid JOIN pg_class parent ON parent.oid=c.confrelid
                WHERE c.contype='f' AND child.relnamespace=(SELECT oid FROM pg_namespace WHERE nspname=current_schema())
                AND child.relname LIKE 'underground_current_%' AND parent.relname IN ('evidence_artifact','ingestion_run','source_endpoint','source')
                """, Integer.class)).isZero();
    }
}
