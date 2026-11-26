package info.lifeinuk.backend.marketindices;

import info.lifeinuk.backend.marketindices.MarketCalendar.Exchange;
import info.lifeinuk.backend.marketindices.MarketCalendar.State;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

/** Sessions from the transcribed official calendars, including holidays, early closes and DST mismatch weeks. */
class MarketCalendarTest {
    @ParameterizedTest(name = "{0} at {1} is {2}, last close {3}")
    @CsvSource({
            // Regular days (2026-11-20 is a Friday).
            "LSE,    2026-11-20T07:59:59Z, CLOSED,  2026-11-19T16:30:00Z",
            "LSE,    2026-11-20T08:00:00Z, OPEN,    ''",
            "LSE,    2026-11-20T16:29:59Z, OPEN,    ''",
            "LSE,    2026-11-20T16:30:00Z, CLOSED,  2026-11-20T16:30:00Z",
            "NYSE,   2026-11-20T14:30:00Z, OPEN,    ''",
            "NYSE,   2026-11-23T13:00:00Z, CLOSED,  2026-11-20T21:00:00Z",
            // UK clocks change 25 Oct 2026, US clocks 1 Nov 2026: in between, New York opens at 13:30 UTC.
            "NYSE,   2026-10-27T13:29:59Z, CLOSED,  2026-10-26T20:00:00Z",
            "NYSE,   2026-10-27T13:30:00Z, OPEN,    ''",
            "NYSE,   2026-11-03T13:30:00Z, CLOSED,  2026-11-02T21:00:00Z",
            "NYSE,   2026-11-03T14:30:00Z, OPEN,    ''",
            "LSE,    2026-10-27T08:00:00Z, OPEN,    ''",
            // Thanksgiving closure and the 13:00 ET early close the next day.
            "NYSE,   2026-11-26T16:00:00Z, CLOSED,  2026-11-25T21:00:00Z",
            "NASDAQ, 2026-11-26T16:00:00Z, CLOSED,  2026-11-25T21:00:00Z",
            "NYSE,   2026-11-27T17:59:59Z, OPEN,    ''",
            "NYSE,   2026-11-27T18:00:00Z, CLOSED,  2026-11-27T18:00:00Z",
            "NASDAQ, 2026-11-27T18:30:00Z, CLOSED,  2026-11-27T18:00:00Z",
            // LSE half day on Christmas Eve, then Christmas and the Boxing Day substitute.
            "LSE,    2026-12-24T12:29:59Z, OPEN,    ''",
            "LSE,    2026-12-24T12:30:00Z, CLOSED,  2026-12-24T12:30:00Z",
            "LSE,    2026-12-28T10:00:00Z, CLOSED,  2026-12-24T12:30:00Z",
            "LSE,    2026-12-29T08:00:00Z, OPEN,    ''",
            // 2027 observed holidays.
            "NYSE,   2027-07-05T15:00:00Z, CLOSED,  2027-07-02T20:00:00Z",
            "LSE,    2027-12-28T10:00:00Z, CLOSED,  2027-12-24T12:30:00Z",
    })
    void sessions(Exchange exchange, Instant now, State state, String lastClose) {
        var session = exchange.session(now);
        assertThat(session.state()).isEqualTo(state);
        assertThat(session.lastClose()).isEqualTo(lastClose.isEmpty() ? null : Instant.parse(lastClose));
        if (state == State.OPEN) assertThat(session.openedAt()).isNotNull().isBeforeOrEqualTo(now);
    }

    @ParameterizedTest(name = "{0} at {1} is UNKNOWN")
    @CsvSource({
            "NASDAQ, 2027-01-04T15:00:00Z", // Nasdaq has not published 2027
            "LSE,    2026-08-28T10:00:00Z", // before the LSE page's coverage
            "LSE,    2026-08-31T10:00:00Z", // covered holiday, but the previous session is outside coverage
            "LSE,    2028-01-04T10:00:00Z",
            "NYSE,   2028-01-03T15:00:00Z",
    })
    void outsideVerifiedCoverageTheSessionIsUnknown(Exchange exchange, Instant now) {
        assertThat(exchange.session(now)).isEqualTo(MarketCalendar.Session.UNKNOWN_SESSION);
    }

    @Test
    void everySymbolMapsToItsExchange() {
        assertThat(MarketIndexSymbol.UKX.exchange).isEqualTo(Exchange.LSE);
        assertThat(MarketIndexSymbol.SPX.exchange).isEqualTo(Exchange.NYSE);
        assertThat(MarketIndexSymbol.DJI.exchange).isEqualTo(Exchange.NYSE);
        assertThat(MarketIndexSymbol.IXIC.exchange).isEqualTo(Exchange.NASDAQ);
    }

    /** Crypto trades through weekends, UK and US holidays and every clock change. */
    @ParameterizedTest(name = "crypto is open at {0}")
    @CsvSource({"2026-11-21T11:20:00Z", "2026-11-22T23:59:59Z", "2026-12-25T12:00:00Z", "2026-11-26T16:00:00Z",
            "2026-03-29T01:30:00Z", "2026-11-01T06:30:00Z", "2030-01-01T00:00:00Z", "2024-06-01T00:00:00Z"})
    void cryptoIsAlwaysOpenWithNoSessionStart(String at) {
        var session = Exchange.CRYPTO_24_7.session(Instant.parse(at));
        assertThat(session.state()).isEqualTo(State.OPEN);
        assertThat(session.openedAt()).isNull();
        assertThat(session.lastClose()).isNull();
    }

    @Test
    void stockExchangesKeepTheirWeekendClosureAndOnlyCryptoIsContinuous() {
        Instant saturday = Instant.parse("2026-11-21T11:20:00Z");
        for (var exchange : Exchange.values()) {
            assertThat(exchange.continuous).isEqualTo(exchange == Exchange.CRYPTO_24_7);
            if (!exchange.continuous) assertThat(exchange.session(saturday).state()).isEqualTo(State.CLOSED);
        }
        assertThat(MarketIndexSymbol.BTCUSD.crypto()).isTrue();
        assertThat(MarketIndexSymbol.ETHUSD.crypto()).isTrue();
        for (var symbol : List.of(MarketIndexSymbol.UKX, MarketIndexSymbol.SPX, MarketIndexSymbol.IXIC, MarketIndexSymbol.DJI)) {
            assertThat(symbol.crypto()).isFalse();
        }
    }
}
