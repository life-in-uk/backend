package info.lifeinuk.backend.roads;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static info.lifeinuk.backend.roads.NationalHighwaysRoadClosuresInterpretation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Deterministic read-time currency. Record identities, statuses and the 15-minute validity windows mirror the
 * retained Issue #26 smoke evidence (run ce9a9a67…, snapshot 2026-10-04T12:37:37Z); no provider payload is copied.
 */
class RoadsCurrencyTest {
    static final Instant SNAPSHOT = Instant.parse("2026-10-04T12:37:37.802125Z");
    static final double LAT = 52.193516, LON = -0.908380;

    static Validity validity(String status, String start, String end) {
        return new Validity(Optional.ofNullable(status), Optional.ofNullable(start).map(Instant::parse),
                Optional.ofNullable(end).map(Instant::parse));
    }

    static RoadsCurrentState.Record record(String situation, String id, Optional<Validity> validity) {
        var geometry = new Geometry("ESPG::4326", 2, "52.193516 -0.908380 52.193682 -0.908629",
                List.of(List.of(new BigDecimal("52.193516"), new BigDecimal("-0.908380")),
                        List.of(new BigDecimal("52.193682"), new BigDecimal("-0.908629"))));
        var location = new LinearLocation(Optional.of(geometry), Optional.of("M1 northbound"), List.of(), List.of());
        var closure = new Closure(id, Optional.of("1"), Optional.empty(), Optional.empty(), Optional.of("certain"),
                Optional.of("mandatory"), Optional.of("Signs and Signals"), Optional.of(new Code("laneClosures", Optional.empty())),
                validity, Optional.empty(), List.of("laneClosures"), List.of(location));
        return new RoadsCurrentState.Record(situation, Optional.empty(), Optional.of("noRestriction"), Optional.of("real"), closure);
    }

    static RoadsCurrentState snapshot(UUID run, Instant at, RoadsCurrentState.Record... records) {
        return new RoadsCurrentState(run, UUID.randomUUID(), at, at.plusSeconds(60), 1, List.of(records));
    }

    static List<String> returned(RoadsCurrentState state, Instant now) {
        RoadsCurrentStates states = mock(RoadsCurrentStates.class);
        when(states.current()).thenReturn(Optional.of(state));
        return new RoadsQuery(states, Clock.fixed(now, ZoneOffset.UTC)).read(LAT, LON).orElseThrow()
                .disruptions().stream().map(RoadsResponse.Disruption::recordId).toList();
    }

    @ParameterizedTest(name = "{0} [{1} .. {2}] at {3} -> current={4}")
    @CsvSource(nullValues = "NULL", value = {
        // Explicit status overrides the time specification (contract + DATEX II).
        "active,                    2026-10-04T09:54:23Z, 2026-10-04T10:09:23Z, 2026-10-04T12:37:37Z, true",
        "active,                    2026-10-04T12:14:02Z, 2026-10-04T12:29:02Z, 2026-10-04T12:20:00Z, true",
        "active,                    2026-10-04T13:00:00Z, NULL,                 2026-10-04T12:37:37Z, true",
        "suspended,                 2026-10-04T11:56:19Z, 2026-10-04T12:11:19Z, 2026-10-04T12:37:37Z, false",
        "suspended,                 2026-10-04T12:25:00Z, 2026-10-04T12:40:00Z, 2026-10-04T12:30:00Z, false",
        "suspended,                 2026-10-04T12:25:00Z, NULL,                 2026-10-04T12:30:00Z, false",
        "planned,                   2026-10-04T12:00:00Z, 2026-10-04T13:00:00Z, 2026-10-04T12:30:00Z, false",
        // Only an explicit time-spec status is evaluated against its inclusive bounding period.
        "definedByValidityTimeSpec, 2026-10-04T12:00:00Z, 2026-10-04T13:00:00Z, 2026-10-04T11:59:59.999999999Z, false",
        "definedByValidityTimeSpec, 2026-10-04T12:00:00Z, 2026-10-04T13:00:00Z, 2026-10-04T12:00:00Z, true",
        "definedByValidityTimeSpec, 2026-10-04T12:00:00Z, 2026-10-04T13:00:00Z, 2026-10-04T13:00:00Z, true",
        "definedByValidityTimeSpec, 2026-10-04T12:00:00Z, 2026-10-04T13:00:00Z, 2026-10-04T13:00:00.000000001Z, false",
        "definedByValidityTimeSpec, 2026-10-04T12:00:00Z, NULL,                 2027-01-01T00:00:00Z, true",
        // Absent or unrecognised status is not proof of non-currency.
        "NULL,                      2026-10-04T09:00:00Z, 2026-10-04T09:15:00Z, 2026-10-04T12:37:37Z, true",
        "futureProviderValue,       2026-10-04T09:00:00Z, 2026-10-04T09:15:00Z, 2026-10-04T12:37:37Z, true",
        "Suspended,                 2026-10-04T09:00:00Z, 2026-10-04T09:15:00Z, 2026-10-04T12:37:37Z, true"
    })
    void validityStatusAndTimeSpecification(String status, String start, String end, String at, boolean current) {
        assertThat(NationalHighwaysValidity.current(Optional.of(validity(status, start, end)), Instant.parse(at))).isEqualTo(current);
    }

    @Test
    void recordWithoutValidityIsNotWithheld() {
        assertThat(NationalHighwaysValidity.current(Optional.empty(), SNAPSHOT)).isTrue();
    }

    @Test
    void observedSmokeShapesReturnActiveRecordsAndWithholdSuspendedOnesWithoutChangingStoredFacts() {
        var state = snapshot(UUID.randomUUID(), SNAPSHOT,
                // active, end 2h28m before the snapshot: still current per explicit status.
                record("signs/M25-S4109B", "active-past-end", Optional.of(validity("active", "2026-10-04T09:54:23Z", "2026-10-04T10:09:23Z"))),
                // suspended with end already passed (the record seen in frontend testing).
                record("signs/M1-S3010A", "suspended-past-end", Optional.of(validity("suspended", "2026-10-04T11:56:19Z", "2026-10-04T12:11:19Z"))),
                // suspended although its end is still in the future: status, not time, decides.
                record("signs/M25-S5050A", "suspended-future-end", Optional.of(validity("suspended", "2026-10-04T12:25:00Z", "2026-10-04T12:40:00Z"))),
                record("signs/M20-S6465A", "active-recent", Optional.of(validity("active", "2026-10-04T12:19:00Z", "2026-10-04T12:34:00Z"))));
        var before = List.copyOf(state.closures());
        // Equal geometry: the existing deterministic order (distance, situation, record) applies.
        assertThat(returned(state, SNAPSHOT)).containsExactly("active-recent", "active-past-end");
        // A later read time cannot revive suspended records or expire active ones.
        assertThat(returned(state, SNAPSHOT.plusSeconds(86_400))).containsExactly("active-recent", "active-past-end");
        assertThat(state.closures()).isEqualTo(before);
    }

    @Test
    void timeSpecRecordsAreEvaluatedAtReadTimeAgainstTheCapturedClock() {
        var state = snapshot(UUID.randomUUID(), SNAPSHOT,
                record("s", "window", Optional.of(validity("definedByValidityTimeSpec", "2026-10-04T12:00:00Z", "2026-10-04T13:00:00Z"))));
        assertThat(returned(state, Instant.parse("2026-10-04T12:59:59Z"))).containsExactly("window");
        assertThat(returned(state, Instant.parse("2026-10-04T13:00:01Z"))).isEmpty();
    }

    @Test
    void recordAbsentFromALaterCompleteSnapshotIsNoLongerReturned() {
        var earlier = snapshot(UUID.randomUUID(), SNAPSHOT,
                record("signs/M1-S2674A", "ended", Optional.of(validity("active", "2026-10-04T12:01:00Z", "2026-10-04T12:16:00Z"))),
                record("signs/M3-S1346K", "continuing", Optional.of(validity("active", "2026-10-04T11:37:00Z", "2026-10-04T11:52:00Z"))));
        var later = snapshot(UUID.randomUUID(), SNAPSHOT.plusSeconds(600),
                record("signs/M3-S1346K", "continuing", Optional.of(validity("active", "2026-10-04T11:37:00Z", "2026-10-04T11:52:00Z"))));
        Instant now = SNAPSHOT.plusSeconds(700);
        assertThat(returned(earlier, now)).containsExactly("ended", "continuing");
        assertThat(returned(later, now)).containsExactly("continuing");
    }

    @Test
    void withholdingKeepsNearestFirstOrderingAndRelevanceForTheRemainingRecords() {
        var state = snapshot(UUID.randomUUID(), SNAPSHOT,
                record("b", "2", Optional.of(validity("active", "2026-10-04T12:00:00Z", "2026-10-04T12:15:00Z"))),
                record("a", "1", Optional.of(validity("suspended", "2026-10-04T12:00:00Z", "2026-10-04T12:15:00Z"))),
                record("a", "3", Optional.of(validity("active", "2026-10-04T12:00:00Z", "2026-10-04T12:15:00Z"))));
        RoadsCurrentStates states = mock(RoadsCurrentStates.class);
        when(states.current()).thenReturn(Optional.of(state));
        var response = new RoadsQuery(states, Clock.fixed(SNAPSHOT, ZoneOffset.UTC)).read(LAT, LON).orElseThrow();
        assertThat(response.disruptions()).extracting(RoadsResponse.Disruption::recordId).containsExactly("3", "2");
        assertThat(response.snapshotAt()).isEqualTo(SNAPSHOT);
        assertThat(response.relevanceRadiusMeters()).isEqualTo(15_000);
        assertThat(response.disruptions()).allSatisfy(d -> assertThat(d.status()).isEqualTo("active"));
    }
}
