package info.lifeinuk.backend.refresh;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RefreshLoopTest {
    private static final Duration TICK = Duration.ofMillis(5);

    @Test
    void stopsAfterMaxRunsOfProviderWorkAndDoesNotCountSkippedTicks() throws Exception {
        var outcomes = new ArrayDeque<>(List.of(RefreshPolicy.Outcome.SKIPPED, RefreshPolicy.Outcome.SUCCESS,
                RefreshPolicy.Outcome.SKIPPED, RefreshPolicy.Outcome.FAILED, RefreshPolicy.Outcome.SUCCESS,
                RefreshPolicy.Outcome.SUCCESS, RefreshPolicy.Outcome.SUCCESS));
        var calls = new AtomicInteger();
        var loop = new RefreshLoop("test", new RefreshPolicy(TICK, TICK), 3, () -> {
            calls.incrementAndGet();
            return outcomes.removeFirst();
        });
        loop.start(Duration.ZERO);
        assertThat(loop.awaitStopped(Duration.ofSeconds(5))).isTrue();
        assertThat(loop.runs()).isEqualTo(3);
        assertThat(calls.get()).isEqualTo(5);
        assertThat(loop.isRunning()).isFalse();
    }

    @Test
    void unexpectedExceptionsCountAsFailuresAndTheScheduleContinues() throws Exception {
        var calls = new AtomicInteger();
        var loop = new RefreshLoop("test", new RefreshPolicy(TICK, TICK), 3, () -> {
            if (calls.incrementAndGet() < 3) {
                throw new IllegalStateException("boom");
            }
            return RefreshPolicy.Outcome.SUCCESS;
        });
        loop.start(Duration.ZERO);
        assertThat(loop.awaitStopped(Duration.ofSeconds(5))).isTrue();
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void runsNeverOverlapAndStopEndsTheSchedule() throws Exception {
        var active = new AtomicInteger();
        var maxActive = new AtomicInteger();
        var three = new CountDownLatch(3);
        var loop = new RefreshLoop("test", new RefreshPolicy(Duration.ofMillis(1), Duration.ofMillis(1)), 0, () -> {
            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            active.decrementAndGet();
            three.countDown();
            return RefreshPolicy.Outcome.SUCCESS;
        });
        loop.start(Duration.ZERO);
        assertThat(three.await(5, TimeUnit.SECONDS)).isTrue();
        loop.stop();
        assertThat(loop.awaitStopped(Duration.ofSeconds(5))).isTrue();
        assertThat(maxActive.get()).isEqualTo(1);
        int after = loop.runs();
        Thread.sleep(30);
        assertThat(loop.runs()).isEqualTo(after);
    }
}
