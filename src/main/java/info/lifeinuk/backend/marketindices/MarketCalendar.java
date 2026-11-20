package info.lifeinuk.backend.marketindices;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;

/**
 * Regular sessions, full-day closures and early closes, transcribed from each exchange's own published calendar
 * (sources in docs/market-indices.md, checked 2026-11-20). Each exchange is only "known" inside its verified
 * coverage window; outside it the session is {@link State#UNKNOWN} and nothing can be rated FRESH or MARKET_CLOSED.
 * Extending coverage means transcribing the next published year and updating the tests, never guessing.
 */
final class MarketCalendar {
    private MarketCalendar() { }

    enum State { OPEN, CLOSED, UNKNOWN }

    /**
     * {@code openedAt}: the current session's open, only when OPEN. {@code lastClose}: the most recent scheduled
     * session close at or before now, only when CLOSED.
     */
    record Session(State state, Instant openedAt, Instant lastClose) {
        static final Session UNKNOWN_SESSION = new Session(State.UNKNOWN, null, null);
    }

    enum Exchange {
        // LSE: business-days page lists 31 Aug 2026 onwards and all of 2027; half days: closing from 12:30 London.
        LSE(ZoneId.of("Europe/London"), LocalTime.of(8, 0), LocalTime.of(16, 30),
                LocalDate.of(2026, 8, 31), LocalDate.of(2027, 12, 31),
                Set.of(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 12, 25), LocalDate.of(2026, 12, 28),
                        LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 26), LocalDate.of(2027, 3, 29),
                        LocalDate.of(2027, 5, 3), LocalDate.of(2027, 5, 31), LocalDate.of(2027, 8, 30),
                        LocalDate.of(2027, 12, 27), LocalDate.of(2027, 12, 28)),
                Map.of(LocalDate.of(2026, 12, 24), LocalTime.of(12, 30), LocalDate.of(2026, 12, 31), LocalTime.of(12, 30),
                        LocalDate.of(2027, 12, 24), LocalTime.of(12, 30), LocalDate.of(2027, 12, 31), LocalTime.of(12, 30))),
        // NYSE hours-calendars page: 2026 and 2027 holidays; early closes 1:00 p.m. ET.
        NYSE(ZoneId.of("America/New_York"), LocalTime.of(9, 30), LocalTime.of(16, 0),
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31),
                Set.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 19), LocalDate.of(2026, 2, 16),
                        LocalDate.of(2026, 4, 3), LocalDate.of(2026, 5, 25), LocalDate.of(2026, 6, 19),
                        LocalDate.of(2026, 7, 3), LocalDate.of(2026, 9, 7), LocalDate.of(2026, 11, 26),
                        LocalDate.of(2026, 12, 25),
                        LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 18), LocalDate.of(2027, 2, 15),
                        LocalDate.of(2027, 3, 26), LocalDate.of(2027, 5, 31), LocalDate.of(2027, 6, 18),
                        LocalDate.of(2027, 7, 5), LocalDate.of(2027, 9, 6), LocalDate.of(2027, 11, 25),
                        LocalDate.of(2027, 12, 24)),
                Map.of(LocalDate.of(2026, 11, 27), LocalTime.of(13, 0), LocalDate.of(2026, 12, 24), LocalTime.of(13, 0),
                        LocalDate.of(2027, 11, 26), LocalTime.of(13, 0))),
        // Nasdaq Trader holiday schedule: 2026 only (2027 not yet published by Nasdaq).
        NASDAQ(ZoneId.of("America/New_York"), LocalTime.of(9, 30), LocalTime.of(16, 0),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                Set.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 19), LocalDate.of(2026, 2, 16),
                        LocalDate.of(2026, 4, 3), LocalDate.of(2026, 5, 25), LocalDate.of(2026, 6, 19),
                        LocalDate.of(2026, 7, 3), LocalDate.of(2026, 9, 7), LocalDate.of(2026, 11, 26),
                        LocalDate.of(2026, 12, 25)),
                Map.of(LocalDate.of(2026, 11, 27), LocalTime.of(13, 0), LocalDate.of(2026, 12, 24), LocalTime.of(13, 0)));

        final ZoneId zone;
        final LocalTime open;
        final LocalTime close;
        final LocalDate coveredFrom;
        final LocalDate coveredThrough;
        private final Set<LocalDate> closed;
        private final Map<LocalDate, LocalTime> earlyClose;

        Exchange(ZoneId zone, LocalTime open, LocalTime close, LocalDate coveredFrom, LocalDate coveredThrough,
                Set<LocalDate> closed, Map<LocalDate, LocalTime> earlyClose) {
            this.zone = zone;
            this.open = open;
            this.close = close;
            this.coveredFrom = coveredFrom;
            this.coveredThrough = coveredThrough;
            this.closed = closed;
            this.earlyClose = earlyClose;
        }

        boolean covers(LocalDate date) { return !date.isBefore(coveredFrom) && !date.isAfter(coveredThrough); }

        private boolean tradingDay(LocalDate date) {
            var day = date.getDayOfWeek();
            return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY && !closed.contains(date);
        }

        private Instant at(LocalDate date, LocalTime time) { return date.atTime(time).atZone(zone).toInstant(); }

        private Instant closeOf(LocalDate date) { return at(date, earlyClose.getOrDefault(date, close)); }

        Session session(Instant now) {
            LocalDate today = now.atZone(zone).toLocalDate();
            if (!covers(today)) return Session.UNKNOWN_SESSION;
            if (tradingDay(today)) {
                if (!now.isBefore(at(today, open)) && now.isBefore(closeOf(today))) return new Session(State.OPEN, at(today, open), null);
                if (!now.isBefore(closeOf(today))) return new Session(State.CLOSED, null, closeOf(today));
            }
            for (LocalDate day = today.minusDays(1); !day.isBefore(today.minusDays(14)); day = day.minusDays(1)) {
                if (!covers(day)) return Session.UNKNOWN_SESSION;
                if (tradingDay(day)) return new Session(State.CLOSED, null, closeOf(day));
            }
            return Session.UNKNOWN_SESSION;
        }
    }
}
