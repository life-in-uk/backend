package info.lifeinuk.backend.refresh;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import info.lifeinuk.backend.acquisition.NationalHighwaysRoadClosuresAcquisition;
import info.lifeinuk.backend.acquisition.TflUndergroundAcquisition;
import info.lifeinuk.backend.support.IsolatedPostgres;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Real PostgreSQL advisory locks; providers are mocks, so no network is contacted. */
@SpringBootTest(properties = {
    "spring.flyway.schemas=travel_refresh_tests", "spring.flyway.default-schema=travel_refresh_tests",
    "spring.jpa.properties.hibernate.default_schema=travel_refresh_tests",
    "spring.datasource.hikari.schema=travel_refresh_tests"
})
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
class TravelRefreshIntegrationTest {
    @Autowired TravelRefreshScheduler scheduler;
    @Autowired RefreshLock lock;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean TflUndergroundAcquisition underground;
    @MockitoBean NationalHighwaysRoadClosuresAcquisition roads;

    @Test
    void refreshIsOffByDefaultAndNothingIsAcquiredAtStartup() {
        assertThat(scheduler.scheduleCount()).isZero();
        verifyNoInteractions(underground, roads);
    }

    @Test
    void aSecondRunnerIsSkippedWhileTheLockIsHeldAndTheLockIsReleasedAfterwards() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var first = CompletableFuture.supplyAsync(() -> lock.runExclusively(RefreshLock.UNDERGROUND_KEY, () -> {
            calls.incrementAndGet();
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return RefreshPolicy.Outcome.SUCCESS;
        }));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(lock.runExclusively(RefreshLock.UNDERGROUND_KEY, () -> {
            calls.incrementAndGet();
            return RefreshPolicy.Outcome.SUCCESS;
        })).isEqualTo(RefreshPolicy.Outcome.SKIPPED);
        // A different source's lock is independent.
        assertThat(lock.runExclusively(RefreshLock.ROADS_KEY, () -> RefreshPolicy.Outcome.SUCCESS))
                .isEqualTo(RefreshPolicy.Outcome.SUCCESS);
        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(RefreshPolicy.Outcome.SUCCESS);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(lock.runExclusively(RefreshLock.UNDERGROUND_KEY, () -> RefreshPolicy.Outcome.FAILED))
                .isEqualTo(RefreshPolicy.Outcome.FAILED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory'", Integer.class))
                .isZero();
    }

    @Test
    void theLockIsReleasedWhenTheTaskThrows() {
        assertThatIllegalStateException().isThrownBy(() -> lock.runExclusively(RefreshLock.ROADS_KEY, () -> {
            throw new IllegalStateException("task failure");
        }));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory'", Integer.class))
                .isZero();
        assertThat(lock.runExclusively(RefreshLock.ROADS_KEY, () -> RefreshPolicy.Outcome.SUCCESS))
                .isEqualTo(RefreshPolicy.Outcome.SUCCESS);
    }
}
