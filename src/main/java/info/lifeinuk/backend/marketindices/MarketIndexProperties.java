package info.lifeinuk.backend.marketindices;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Stock index ingestion and the public latest-value read (docs/market-indices.md). Everything defaults to off:
 * no collector key (POST answers 503), no accepted source (every batch is rejected), public read disabled (404) and
 * no public source. Invalid or unsafe combinations fail startup instead of degrading silently.
 *
 * <p>{@code collectorKeys} holds SHA-256 hashes, never secrets: {@code keyId:sha256hex}, at most two (rotation).
 */
@ConfigurationProperties("life-in-uk.market-indices")
public record MarketIndexProperties(
        @DefaultValue("") String collectorKeys,
        @DefaultValue("") List<String> acceptedSources,
        @DefaultValue("false") boolean publicEnabled,
        @DefaultValue("") List<String> publicSources,
        @DefaultValue("") String attribution,
        @DefaultValue("P30D") Duration maxBackfill,
        @DefaultValue("P730D") Duration retention,
        @DefaultValue("PT20M") Duration freshWithin,
        @DefaultValue("PT60M") Duration staleAfter,
        @DefaultValue("PT30M") Duration closeTolerance,
        @DefaultValue("PT6M") Duration heartbeatTimeout,
        @DefaultValue("PT2M") Duration futureSkew,
        @DefaultValue("PT5M") Duration collectorClockSkew) {

    static final Pattern SOURCE = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,39}$");
    static final String MOCK_SOURCE = "mock";
    static final Duration MIN_BACKFILL = Duration.ofHours(1);
    static final Duration MAX_BACKFILL = Duration.ofDays(365);
    static final Duration MIN_RETENTION = Duration.ofDays(30);
    static final Duration MAX_RETENTION = Duration.ofDays(3650);
    /** Retention must exceed the backfill window by at least this much, so accepted data is never purged at once. */
    static final Duration RETENTION_MARGIN = Duration.ofDays(1);

    public MarketIndexProperties {
        collectorKeys = collectorKeys == null ? "" : collectorKeys.strip();
        acceptedSources = sources(acceptedSources, "accepted");
        publicSources = sources(publicSources, "public");
        attribution = attribution == null ? "" : attribution.strip();
        if (attribution.length() > 500) throw new IllegalArgumentException("Market index attribution must be at most 500 characters");
        if (publicSources.contains(MOCK_SOURCE)) {
            throw new IllegalArgumentException("Mock market index data can never be a public source");
        }
        if (publicEnabled && publicSources.isEmpty()) {
            throw new IllegalArgumentException("Public market index read needs at least one public source");
        }
        within(maxBackfill, MIN_BACKFILL, MAX_BACKFILL, "max-backfill");
        within(retention, MIN_RETENTION, MAX_RETENTION, "retention");
        if (retention.compareTo(maxBackfill.plus(RETENTION_MARGIN)) < 0) {
            throw new IllegalArgumentException("Market index retention must exceed max-backfill by at least one day");
        }
        within(freshWithin, Duration.ofMinutes(1), Duration.ofHours(2), "fresh-within");
        within(staleAfter, Duration.ofMinutes(2), Duration.ofHours(24), "stale-after");
        if (staleAfter.compareTo(freshWithin) <= 0) throw new IllegalArgumentException("stale-after must exceed fresh-within");
        within(closeTolerance, Duration.ZERO, Duration.ofHours(2), "close-tolerance");
        within(heartbeatTimeout, Duration.ofMinutes(1), Duration.ofHours(2), "heartbeat-timeout");
        within(futureSkew, Duration.ZERO, Duration.ofMinutes(10), "future-skew");
        within(collectorClockSkew, Duration.ofSeconds(30), Duration.ofMinutes(30), "collector-clock-skew");
    }

    private static List<String> sources(List<String> raw, String kind) {
        if (raw == null) return List.of();
        var cleaned = raw.stream().map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
        for (String source : cleaned) {
            if (!SOURCE.matcher(source).matches()) throw new IllegalArgumentException("Invalid " + kind + " market index source");
        }
        return cleaned;
    }

    private static void within(Duration value, Duration min, Duration max, String name) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException("Market index " + name + " must be between " + min + " and " + max);
        }
    }

    @Override
    public String toString() {
        return "MarketIndexProperties[collectorKeys=" + (collectorKeys.isEmpty() ? "<absent>" : "<hashes>")
                + ", acceptedSources=" + acceptedSources + ", publicEnabled=" + publicEnabled
                + ", publicSources=" + publicSources + ", maxBackfill=" + maxBackfill + ", retention=" + retention + "]";
    }
}
