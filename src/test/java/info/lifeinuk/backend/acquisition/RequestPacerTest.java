package info.lifeinuk.backend.acquisition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RequestPacerTest {
    @Test
    void backToBackRequestsNeverExceedTheProviderLimitInAnyRollingMinute() throws Exception {
        var now = new AtomicLong(5_000_000_000L);
        var pacer = new RequestPacer(NationalHighwaysRoadClosuresAcquisition.MIN_REQUEST_SPACING, now::get,
                duration -> now.addAndGet(duration.toNanos()));
        var issued = new ArrayList<Long>();
        for (int request = 0; request < 40; request++) {
            pacer.awaitTurn();
            issued.add(now.get());
            now.addAndGet(Duration.ofMillis(150).toNanos()); // fast provider responses: the pacer must still hold
        }
        long minute = Duration.ofMinutes(1).toNanos();
        for (int first = 0; first < issued.size(); first++) {
            int inWindow = 0;
            for (long at : issued) {
                if (at >= issued.get(first) && at < issued.get(first) + minute) { inWindow++; }
            }
            assertThat(inWindow).isLessThanOrEqualTo(9).isLessThan(10);
        }
    }

    @Test
    void anIdleGapNeedsNoWaitAndInterruptionPropagates() throws Exception {
        var now = new AtomicLong();
        var sleeps = new ArrayList<Duration>();
        var pacer = new RequestPacer(Duration.ofSeconds(7), now::get, sleeps::add);
        pacer.awaitTurn();
        now.addAndGet(Duration.ofSeconds(30).toNanos());
        pacer.awaitTurn();
        assertThat(sleeps).isEmpty();
        var interrupting = new RequestPacer(Duration.ofSeconds(7), () -> 0L, duration -> { throw new InterruptedException(); });
        interrupting.awaitTurn();
        assertThatThrownBy(interrupting::awaitTurn).isInstanceOf(InterruptedException.class);
        assertThatIllegalArgumentException().isThrownBy(() -> new RequestPacer(Duration.ZERO, now::get, sleeps::add));
    }

    @Test
    void configuredBoundsKeepAFullRunWellInsideTheIntendedTenMinuteCadence() {
        Duration worstCaseRun = NationalHighwaysRoadClosuresAcquisition.MIN_REQUEST_SPACING
                .multipliedBy(NationalHighwaysRoadClosuresAcquisition.MAX_PAGES - 1);
        assertThat(worstCaseRun).isLessThan(Duration.ofMinutes(1));
        assertThat(Duration.ofMinutes(1).dividedBy(NationalHighwaysRoadClosuresAcquisition.MIN_REQUEST_SPACING)).isLessThan(10);
    }
}
