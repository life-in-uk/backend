package info.lifeinuk.backend.rail;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class RailDisruptionsTest {
    private RailResponse.Service service(String scheduled, String estimated, Boolean cancelled) {
        return new RailResponse.Service(scheduled, estimated, List.of(new RailResponse.Location("Origin", "ORG")), List.of(new RailResponse.Location("Destination", "DST")), null, null, cancelled, null, null, null, null);
    }
    private RailDisruptions check(String scheduled, String estimated, Boolean cancelled, String direction) {
        return RailDisruptions.from(new RailResponse("Sheffield", "SHF", null, List.of(service(scheduled, estimated, cancelled))), direction);
    }
    @ParameterizedTest @CsvSource({"10:00,10:00,0", "10:00,10:09,0", "10:00,10:10,1", "10:00,10:14,1", "10:00,10:15,1", "10:00,10:16,1", "23:55,00:05,1", "00:05,23:55,0"})
    void thresholds(String scheduled, String estimate, int count) {
        var result = check(scheduled, estimate, false, "departures");
        assertThat(result.disruptions()).hasSize(count);
        if (count == 1) {
            int minutes = result.disruptions().getFirst().delayMinutes();
            assertThat(result.disruptions().getFirst().checkDelayRepay()).isEqualTo(minutes >= 15);
            assertThat(result.disruptions().getFirst().locations().getFirst().name()).isEqualTo("Destination");
        }
    }
    @Test void cancellationAndExplicitDelayed() {
        assertThat(check("10:00", "10:00", true, "arrivals").disruptions().getFirst().status()).isEqualTo("cancelled");
        assertThat(check("10:00", "Cancelled", null, "arrivals").disruptions().getFirst().locations().getFirst().name()).isEqualTo("Origin");
        var delayed = check("10:00", "Delayed", null, "arrivals").disruptions().getFirst();
        assertThat(delayed.delayMinutes()).isNull(); assertThat(delayed.status()).isEqualTo("delayed"); assertThat(delayed.checkDelayRepay()).isFalse();
    }
    @Test void partialCancellationUsesDirectionAppropriateCallingPoints() {
        var point = new RailResponse.CallingPoint("Derby", "DBY", null, null, null, true, null, null);
        var group = new RailResponse.CallingPointGroup(List.of(point), null, null, false);
        var service = new RailResponse.Service("10:00", "On time", null, null, null, null, false, null, null, List.of(group), null);
        var board = new RailResponse("Sheffield", "SHF", null, List.of(service));
        var arrival = RailDisruptions.from(board, "arrivals").disruptions().getFirst();
        assertThat(arrival.status()).isEqualTo("partially_cancelled");
        assertThat(arrival.affectedStops()).containsExactly(new RailResponse.Location("Derby", "DBY"));
        assertThat(arrival.checkDelayRepay()).isTrue();
        assertThat(RailDisruptions.from(board, "departures").disruptions()).isEmpty();
        var departing = new RailResponse.Service("10:00", "On time", null, null, null, null, false, null, null, null, List.of(group));
        assertThat(RailDisruptions.from(new RailResponse("Sheffield", "SHF", null, List.of(departing)), "departures").disruptions().getFirst().status()).isEqualTo("partially_cancelled");
        assertThat(check("10:00", "Cancelled", true, "arrivals").disruptions().getFirst().checkDelayRepay()).isTrue();
    }
    @Test void partialCancellationUsesUnambiguousStopReasonAndRetainsUnknownStops() {
        var point = new RailResponse.CallingPoint(null, null, null, null, null, true, null, "Stop unavailable");
        var group = new RailResponse.CallingPointGroup(List.of(point), null, null, false);
        var service = new RailResponse.Service("10:00", "On time", null, null, null, null, false, null, "Service reason", List.of(group), null);
        var result = RailDisruptions.from(new RailResponse("Sheffield", "SHF", null, List.of(service)), "arrivals").disruptions().getFirst();
        assertThat(result.affectedStops()).isEmpty();
        assertThat(result.reason()).isEqualTo("Stop unavailable");
        var other = new RailResponse.CallingPoint("Derby", "DBY", null, null, null, true, null, "Different reason");
        var mixed = new RailResponse.CallingPointGroup(List.of(point, other), null, null, false);
        var mixedService = new RailResponse.Service("10:00", "On time", null, null, null, null, false, null, "Service reason", List.of(mixed), null);
        assertThat(RailDisruptions.from(new RailResponse("Sheffield", "SHF", null, List.of(mixedService)), "arrivals").disruptions().getFirst().reason()).isEqualTo("Service reason");
    }
    @Test void unknownTimesAndEmptyBoard() {
        for (String estimate : new String[]{null, "Unknown", "25:13", "unconfirmed"}) {
            var board = check("10:00", estimate, null, "arrivals");
            assertThat(board.disruptions()).isEmpty(); assertThat(board.unknownTimingCount()).isEqualTo(1);
        }
        assertThat(check("10:00", "On time", null, "arrivals").unknownTimingCount()).isZero();
        assertThat(RailDisruptions.from(new RailResponse("Sheffield", "SHF", null, List.of()), "arrivals").disruptions()).isEmpty();
    }
}
