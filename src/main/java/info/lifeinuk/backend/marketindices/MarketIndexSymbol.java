package info.lifeinuk.backend.marketindices;

import java.util.Optional;

/** The fixed allowlist, in public display order. Adding a symbol needs a migration (V17 CHECK) and owner approval. */
public enum MarketIndexSymbol {
    UKX("FTSE 100", MarketCalendar.Exchange.LSE),
    SPX("S&P 500", MarketCalendar.Exchange.NYSE),
    IXIC("Nasdaq Composite", MarketCalendar.Exchange.NASDAQ),
    DJI("Dow Jones Industrial Average", MarketCalendar.Exchange.NYSE);

    final String displayName;
    final MarketCalendar.Exchange exchange;

    MarketIndexSymbol(String displayName, MarketCalendar.Exchange exchange) {
        this.displayName = displayName;
        this.exchange = exchange;
    }

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
