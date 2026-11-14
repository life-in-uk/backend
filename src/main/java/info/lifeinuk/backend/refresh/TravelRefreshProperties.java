package info.lifeinuk.backend.refresh;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Travel Current State refresh. Both schedules are OFF unless explicitly enabled; enabling is an owner
 * operational decision (provider qualification, retention and cost), not a code default.
 *
 * <p>Intervals have hard floors so configuration cannot exceed the reviewed provider budgets: TfL one
 * request per run, National Highways at most eight paced requests per run (10 requests/minute per key).
 * {@code maxRuns} bounds a controlled session (0 = no run cap).
 */
@ConfigurationProperties("life-in-uk.travel-refresh")
public record TravelRefreshProperties(
        @DefaultValue Schedule underground,
        @DefaultValue Schedule roads) {

    static final Duration UNDERGROUND_DEFAULT = Duration.ofMinutes(2);
    static final Duration UNDERGROUND_FLOOR = Duration.ofMinutes(1);
    static final Duration UNDERGROUND_MAX_BACKOFF = Duration.ofMinutes(30);
    static final Duration ROADS_DEFAULT = Duration.ofMinutes(10);
    static final Duration ROADS_FLOOR = Duration.ofMinutes(5);
    static final Duration ROADS_MAX_BACKOFF = Duration.ofMinutes(60);
    static final Duration BACKOFF_CEILING = Duration.ofHours(6);
    static final int MAX_RUN_CAP = 100_000;

    public TravelRefreshProperties {
        underground = underground.resolve("underground", UNDERGROUND_DEFAULT, UNDERGROUND_FLOOR, UNDERGROUND_MAX_BACKOFF);
        roads = roads.resolve("roads", ROADS_DEFAULT, ROADS_FLOOR, ROADS_MAX_BACKOFF);
    }

    /** Null values take the source's reviewed defaults; out-of-range values fail startup. */
    public record Schedule(
            @DefaultValue("false") boolean enabled,
            Duration interval,
            Duration maxBackoff,
            @DefaultValue("0") int maxRuns,
            @DefaultValue("PT15S") Duration initialDelay) {

        Schedule resolve(String name, Duration defaultInterval, Duration floor, Duration defaultMaxBackoff) {
            Duration resolvedInterval = interval == null ? defaultInterval : interval;
            Duration resolvedBackoff = maxBackoff == null ? defaultMaxBackoff : maxBackoff;
            if (resolvedInterval.compareTo(floor) < 0) {
                throw new IllegalArgumentException(name + " refresh interval must be at least " + floor);
            }
            if (resolvedBackoff.compareTo(resolvedInterval) < 0 || resolvedBackoff.compareTo(BACKOFF_CEILING) > 0) {
                throw new IllegalArgumentException(name + " refresh max-backoff must be between the interval and "
                        + BACKOFF_CEILING);
            }
            if (maxRuns < 0 || maxRuns > MAX_RUN_CAP) {
                throw new IllegalArgumentException(name + " refresh max-runs must be between 0 and " + MAX_RUN_CAP);
            }
            if (initialDelay == null || initialDelay.isNegative() || initialDelay.compareTo(Duration.ofMinutes(10)) > 0) {
                throw new IllegalArgumentException(name + " refresh initial-delay must be between 0 and PT10M");
            }
            return new Schedule(enabled, resolvedInterval, resolvedBackoff, maxRuns, initialDelay);
        }
    }
}
