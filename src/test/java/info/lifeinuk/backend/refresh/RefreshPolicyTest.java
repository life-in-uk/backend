package info.lifeinuk.backend.refresh;

import static info.lifeinuk.backend.refresh.RefreshPolicy.Outcome.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RefreshPolicyTest {
    private static final Duration TWO_MINUTES = Duration.ofMinutes(2);
    private static final Duration THIRTY_MINUTES = Duration.ofMinutes(30);

    @Test
    void successKeepsTheIntervalAndResetsFailures() {
        var policy = new RefreshPolicy(TWO_MINUTES, THIRTY_MINUTES);
        assertThat(policy.next(SUCCESS)).isEqualTo(TWO_MINUTES);
        policy.next(FAILED);
        policy.next(FAILED);
        policy.next(FAILED);
        assertThat(policy.next(SUCCESS)).isEqualTo(TWO_MINUTES);
        assertThat(policy.consecutiveFailures()).isZero();
        assertThat(policy.next(FAILED)).isEqualTo(TWO_MINUTES);
    }

    @Test
    void failuresBackOffFromTheThirdAndAreCapped() {
        var policy = new RefreshPolicy(TWO_MINUTES, THIRTY_MINUTES);
        assertThat(policy.next(FAILED)).isEqualTo(TWO_MINUTES);
        assertThat(policy.next(FAILED)).isEqualTo(TWO_MINUTES);
        assertThat(policy.next(FAILED)).isEqualTo(Duration.ofMinutes(4));
        assertThat(policy.next(FAILED)).isEqualTo(Duration.ofMinutes(8));
        assertThat(policy.next(FAILED)).isEqualTo(Duration.ofMinutes(16));
        assertThat(policy.next(FAILED)).isEqualTo(THIRTY_MINUTES);
        for (int i = 0; i < 100; i++) {
            assertThat(policy.next(FAILED)).isEqualTo(THIRTY_MINUTES);
        }
    }

    @Test
    void rateLimitBacksOffImmediately() {
        var policy = new RefreshPolicy(Duration.ofMinutes(10), Duration.ofMinutes(60));
        assertThat(policy.next(RATE_LIMITED)).isEqualTo(Duration.ofMinutes(20));
        assertThat(policy.next(RATE_LIMITED)).isEqualTo(Duration.ofMinutes(20));
        assertThat(policy.next(RATE_LIMITED)).isEqualTo(Duration.ofMinutes(20));
        assertThat(policy.next(RATE_LIMITED)).isEqualTo(Duration.ofMinutes(40));
        assertThat(policy.next(RATE_LIMITED)).isEqualTo(Duration.ofMinutes(60));
        assertThat(policy.next(SUCCESS)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void skippedTicksNeitherCountNorResetFailures() {
        var policy = new RefreshPolicy(TWO_MINUTES, THIRTY_MINUTES);
        policy.next(FAILED);
        policy.next(FAILED);
        assertThat(policy.next(SKIPPED)).isEqualTo(TWO_MINUTES);
        assertThat(policy.consecutiveFailures()).isEqualTo(2);
        assertThat(policy.next(FAILED)).isEqualTo(Duration.ofMinutes(4));
    }

    @Test
    void rejectsInvalidBounds() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RefreshPolicy(Duration.ZERO, THIRTY_MINUTES));
        assertThatIllegalArgumentException().isThrownBy(() -> new RefreshPolicy(THIRTY_MINUTES, TWO_MINUTES));
    }
}
