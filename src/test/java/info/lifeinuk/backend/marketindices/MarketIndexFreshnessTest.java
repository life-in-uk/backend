package info.lifeinuk.backend.marketindices;

import info.lifeinuk.backend.marketindices.MarketIndexFreshness.Status;
import info.lifeinuk.backend.marketindices.MarketIndexSymbol.TimeSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MarketIndexFreshnessTest {
    static final MarketIndexProperties DEFAULTS = MarketIndexPropertiesTest.defaults();
    // Friday 20 Nov 2026, 11:00 London: LSE open since 08:00.
    static final Instant NOW = Instant.parse("2026-11-20T11:00:00Z");
    static final MarketCalendar.Session LSE_OPEN = MarketCalendar.Exchange.LSE.session(NOW);

    static Status status(Instant observedAt, TimeSource source, MarketCalendar.Session session, Instant now) {
        return MarketIndexFreshness.evaluate(observedAt, source, session, now, DEFAULTS).status();
    }

    @Test
    void openSessionUsesObservedAtAge() {
        assertThat(status(NOW.minusSeconds(300), TimeSource.EXCHANGE, LSE_OPEN, NOW)).isEqualTo(Status.FRESH);
        assertThat(status(NOW.minusSeconds(1200), TimeSource.PROVIDER, LSE_OPEN, NOW)).isEqualTo(Status.FRESH);
        assertThat(status(NOW.minusSeconds(1201), TimeSource.PROVIDER, LSE_OPEN, NOW)).isEqualTo(Status.DELAYED);
        assertThat(status(NOW.minusSeconds(3601), TimeSource.PROVIDER, LSE_OPEN, NOW)).isEqualTo(Status.STALE);
    }

    @Test
    void aValueFromBeforeTheSessionOpenedIsNeverFresh() {
        Instant justAfterOpen = Instant.parse("2026-11-20T08:05:00Z");
        var session = MarketCalendar.Exchange.LSE.session(justAfterOpen);
        assertThat(status(Instant.parse("2026-11-20T07:59:00Z"), TimeSource.EXCHANGE, session, justAfterOpen)).isEqualTo(Status.DELAYED);
        assertThat(status(Instant.parse("2026-11-19T16:35:00Z"), TimeSource.EXCHANGE, session, justAfterOpen)).isEqualTo(Status.STALE);
    }

    @Test
    void collectorTimeIsNeverFreshAndOnlyProvablyOldValuesAreStale() {
        assertThat(status(NOW.minusSeconds(10), TimeSource.COLLECTOR, LSE_OPEN, NOW)).isEqualTo(Status.MARKET_TIME_UNKNOWN);
        assertThat(status(NOW.minusSeconds(3601), TimeSource.COLLECTOR, LSE_OPEN, NOW)).isEqualTo(Status.STALE);
    }

    @Test
    void unknownSessionIsNeverFreshOrClosed() {
        var unknown = MarketCalendar.Session.UNKNOWN_SESSION;
        assertThat(status(NOW.minusSeconds(10), TimeSource.EXCHANGE, unknown, NOW)).isEqualTo(Status.MARKET_STATUS_UNKNOWN);
        assertThat(status(NOW.minusSeconds(7200), TimeSource.EXCHANGE, unknown, NOW)).isEqualTo(Status.STALE);
    }

    @Test
    void closedSessionAcceptsOnlyTheLatestCloseWithinTolerance() {
        Instant saturday = Instant.parse("2026-11-21T12:00:00Z");
        var closed = MarketCalendar.Exchange.LSE.session(saturday);
        assertThat(status(Instant.parse("2026-11-20T16:35:00Z"), TimeSource.EXCHANGE, closed, saturday)).isEqualTo(Status.MARKET_CLOSED);
        assertThat(status(Instant.parse("2026-11-20T16:00:00Z"), TimeSource.EXCHANGE, closed, saturday)).isEqualTo(Status.MARKET_CLOSED);
        assertThat(status(Instant.parse("2026-11-20T15:59:59Z"), TimeSource.EXCHANGE, closed, saturday)).isEqualTo(Status.STALE);
        assertThat(status(Instant.parse("2026-11-19T16:35:00Z"), TimeSource.EXCHANGE, closed, saturday)).isEqualTo(Status.STALE);
    }

    @Test
    void earlyCloseAndHolidayAreConservative() {
        Instant afterEarlyClose = Instant.parse("2026-11-27T19:00:00Z");
        var nyse = MarketCalendar.Exchange.NYSE.session(afterEarlyClose);
        assertThat(status(Instant.parse("2026-11-27T18:00:00Z"), TimeSource.EXCHANGE, nyse, afterEarlyClose)).isEqualTo(Status.MARKET_CLOSED);
        Instant thanksgiving = Instant.parse("2026-11-26T17:00:00Z");
        var holiday = MarketCalendar.Exchange.NYSE.session(thanksgiving);
        assertThat(status(Instant.parse("2026-11-25T21:00:00Z"), TimeSource.EXCHANGE, holiday, thanksgiving)).isEqualTo(Status.MARKET_CLOSED);
        assertThat(status(Instant.parse("2026-11-24T21:00:00Z"), TimeSource.EXCHANGE, holiday, thanksgiving)).isEqualTo(Status.STALE);
    }

    @Test
    void missingValueAndFutureTimes() {
        assertThat(MarketIndexFreshness.evaluate(null, null, LSE_OPEN, NOW, DEFAULTS))
                .isEqualTo(new MarketIndexFreshness.Result(Status.UNAVAILABLE, null));
        assertThat(MarketIndexFreshness.evaluate(NOW.plusSeconds(30), TimeSource.EXCHANGE, LSE_OPEN, NOW, DEFAULTS).ageSeconds()).isZero();
    }

    /** Sweeps 2026-2028 every 7 minutes for every exchange: FRESH only ever for a market time in an OPEN, known session. */
    @Test
    void freshRequiresMarketTimeKnownOpenSessionAndSameSessionObservation() {
        for (var exchange : MarketCalendar.Exchange.values()) {
            for (Instant now = Instant.parse("2026-01-01T00:00:00Z"); now.isBefore(Instant.parse("2028-03-01T00:00:00Z"));
                    now = now.plus(Duration.ofMinutes(7))) {
                var session = exchange.session(now);
                for (long ageSeconds : List.of(0L, 600L, 1500L)) {
                    Instant observedAt = now.minusSeconds(ageSeconds);
                    for (var source : TimeSource.values()) {
                        var status = status(observedAt, source, session, now);
                        if (status == Status.FRESH) {
                            assertThat(source).isNotEqualTo(TimeSource.COLLECTOR);
                            assertThat(session.state()).isEqualTo(MarketCalendar.State.OPEN);
                            assertThat(observedAt).isAfterOrEqualTo(session.openedAt());
                        }
                        if (session.state() == MarketCalendar.State.UNKNOWN) {
                            assertThat(status).isNotIn(Status.FRESH, Status.MARKET_CLOSED, Status.DELAYED);
                        }
                    }
                }
            }
        }
    }
}
