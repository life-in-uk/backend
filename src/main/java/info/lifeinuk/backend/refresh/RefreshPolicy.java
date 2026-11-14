package info.lifeinuk.backend.refresh;

import java.time.Duration;
import java.util.Objects;

/**
 * Pure next-delay policy for one refresh schedule. Fixed delay after each run (never overlapping);
 * no retry inside a run, the next tick is the retry.
 *
 * <ul>
 *   <li>SUCCESS: back to the interval, failures reset.</li>
 *   <li>FAILED: the first {@value #FAILURES_BEFORE_BACKOFF} - 1 consecutive failures keep the interval; from
 *       the {@value #FAILURES_BEFORE_BACKOFF}rd the delay doubles per failure, capped at max-backoff.</li>
 *   <li>RATE_LIMITED (provider HTTP 429): immediate doubling, capped at max-backoff.</li>
 *   <li>SKIPPED (another instance holds the run lock): interval, failure count unchanged.</li>
 * </ul>
 */
final class RefreshPolicy {
    static final int FAILURES_BEFORE_BACKOFF = 3;

    enum Outcome { SUCCESS, FAILED, RATE_LIMITED, SKIPPED }

    private final Duration interval;
    private final Duration maxBackoff;
    private int consecutiveFailures;

    RefreshPolicy(Duration interval, Duration maxBackoff) {
        this.interval = Objects.requireNonNull(interval);
        this.maxBackoff = Objects.requireNonNull(maxBackoff);
        if (interval.isNegative() || interval.isZero() || maxBackoff.compareTo(interval) < 0) {
            throw new IllegalArgumentException("Interval must be positive and not exceed max-backoff");
        }
    }

    int consecutiveFailures() { return consecutiveFailures; }

    Duration next(Outcome outcome) {
        return switch (Objects.requireNonNull(outcome)) {
            case SUCCESS -> {
                consecutiveFailures = 0;
                yield interval;
            }
            case SKIPPED -> interval;
            case FAILED -> {
                consecutiveFailures++;
                int doublings = consecutiveFailures - FAILURES_BEFORE_BACKOFF + 1;
                yield doublings <= 0 ? interval : scaled(doublings);
            }
            case RATE_LIMITED -> {
                consecutiveFailures++;
                yield scaled(Math.max(1, consecutiveFailures - FAILURES_BEFORE_BACKOFF + 1));
            }
        };
    }

    private Duration scaled(int doublings) {
        Duration delay = interval;
        for (int i = 0; i < doublings && delay.compareTo(maxBackoff) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }
}
