package info.lifeinuk.backend.acquisition;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Sequential minimum spacing between provider requests made with one credential. With a spacing above
 * 60s / limit, no rolling minute can contain more than the provider limit, across pages and across runs.
 */
final class RequestPacer {
    @FunctionalInterface
    interface Sleeper { void sleep(Duration duration) throws InterruptedException; }

    private final long spacingNanos;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    private boolean issued;
    private long lastIssuedNanos;

    RequestPacer(Duration spacing, LongSupplier nanoTime, Sleeper sleeper) {
        if (spacing == null || spacing.isNegative() || spacing.isZero()) {
            throw new IllegalArgumentException("A positive spacing is required");
        }
        this.spacingNanos = spacing.toNanos();
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.sleeper = Objects.requireNonNull(sleeper);
    }

    static RequestPacer realTime(Duration spacing) {
        return new RequestPacer(spacing, System::nanoTime, Thread::sleep);
    }

    /** Blocks until the next request may be issued, then records it as issued. */
    synchronized void awaitTurn() throws InterruptedException {
        long now = nanoTime.getAsLong();
        if (issued) {
            long wait = lastIssuedNanos + spacingNanos - now;
            if (wait > 0) {
                sleeper.sleep(Duration.ofNanos(wait));
                now = nanoTime.getAsLong();
            }
        }
        issued = true;
        lastIssuedNanos = now;
    }
}
