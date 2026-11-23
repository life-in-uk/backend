package info.lifeinuk.backend.marketindices;

import java.util.Optional;

/**
 * The fixed allowlist, in public display order. Adding a symbol needs a migration (V17/V18 CHECK) and owner approval.
 * The crypto pairs trade continuously, are priced in USD and carry a rolling 24-hour reference instead of a previous
 * close; the stock indices keep their exchange sessions and previous close.
 */
public enum MarketIndexSymbol {
    UKX("FTSE 100", MarketCalendar.Exchange.LSE),
    SPX("S&P 500", MarketCalendar.Exchange.NYSE),
    IXIC("Nasdaq Composite", MarketCalendar.Exchange.NASDAQ),
    DJI("Dow Jones Industrial Average", MarketCalendar.Exchange.NYSE),
    BTCUSD("Bitcoin", MarketCalendar.Exchange.CRYPTO_24_7),
    ETHUSD("Ethereum", MarketCalendar.Exchange.CRYPTO_24_7);

    static final String NAMES = "UKX, SPX, IXIC, DJI, BTCUSD, ETHUSD";

    final String displayName;
    final MarketCalendar.Exchange exchange;

    MarketIndexSymbol(String displayName, MarketCalendar.Exchange exchange) {
        this.displayName = displayName;
        this.exchange = exchange;
    }

    /** A crypto pair: USD price, continuous market, rolling 24-hour change. */
    boolean crypto() { return exchange.continuous; }

    static Optional<MarketIndexSymbol> parse(String value) {
        for (var symbol : values()) {
            if (symbol.name().equals(value)) return Optional.of(symbol);
        }
        return Optional.empty();
    }

    /** Where {@code observedAt} came from. Only EXCHANGE and PROVIDER are market times. */
    public enum TimeSource {
        EXCHANGE, PROVIDER, COLLECTOR;

        static Optional<TimeSource> parse(String value) {
            for (var source : values()) {
                if (source.name().equals(value)) return Optional.of(source);
            }
            return Optional.empty();
        }
    }
}
