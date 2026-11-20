package info.lifeinuk.backend.marketindices;

import java.time.Duration;
import java.time.Instant;

/**
 * Freshness from {@code observedAt} only (never receipt or collection time), evaluated when a request is served.
 * FRESH needs all of: a market time (EXCHANGE or PROVIDER), a session known from the verified calendar, the session
 * OPEN, and age within fresh-within. Every uncertain case degrades to MARKET_STATUS_UNKNOWN,
 * MARKET_TIME_UNKNOWN or STALE.
 */
final class MarketIndexFreshness {
    private MarketIndexFreshness() { }

    enum Status { FRESH, DELAYED, STALE, MARKET_CLOSED, MARKET_STATUS_UNKNOWN, MARKET_TIME_UNKNOWN, UNAVAILABLE }

    record Result(Status status, Long ageSeconds) { }

    static Result evaluate(Instant observedAt, MarketIndexSymbol.TimeSource timeSource, MarketCalendar.Session session,
            Instant now, MarketIndexProperties properties) {
        if (observedAt == null || timeSource == null) return new Result(Status.UNAVAILABLE, null);
        // A time later than now is not a usable market time (the latest-value query never selects one; this guards
        // every other caller).
        if (observedAt.isAfter(now)) return new Result(Status.MARKET_TIME_UNKNOWN, 0L);
        Duration age = Duration.between(observedAt, now);
        long ageSeconds = age.toSeconds();
        boolean stale = age.compareTo(properties.staleAfter()) > 0;
        // A collector-derived time is at or after the real market time, so its age is a lower bound: if even that is
        // past stale-after the value is certainly stale; otherwise nothing better than "time unknown" can be said.
        if (timeSource == MarketIndexSymbol.TimeSource.COLLECTOR) {
            return new Result(stale ? Status.STALE : Status.MARKET_TIME_UNKNOWN, ageSeconds);
        }
        return switch (session.state()) {
            case UNKNOWN -> new Result(stale ? Status.STALE : Status.MARKET_STATUS_UNKNOWN, ageSeconds);
            // A value from before this session opened (e.g. the previous close) is never FRESH.
            case OPEN -> new Result(stale ? Status.STALE
                    : !observedAt.isBefore(session.openedAt()) && age.compareTo(properties.freshWithin()) <= 0
                    ? Status.FRESH : Status.DELAYED, ageSeconds);
            // The closing value must be stamped within close-tolerance either side of the last scheduled close (early
            // closes included). Earlier is an older session; later (e.g. a weekend or after-hours stamp, or the regular
            // close time on an early-close day) is not a time that session can have produced.
            case CLOSED -> observedAt.isBefore(session.lastClose().minus(properties.closeTolerance()))
                    ? new Result(Status.STALE, ageSeconds)
                    : observedAt.isAfter(session.lastClose().plus(properties.closeTolerance()))
                    ? new Result(Status.MARKET_STATUS_UNKNOWN, ageSeconds)
                    : new Result(Status.MARKET_CLOSED, ageSeconds);
        };
    }
}
