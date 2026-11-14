package info.lifeinuk.backend.rail;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Per-instance request budget for provider board requests, applied after request validation and before the
 * provider is contacted. Fixed one-minute windows:
 *
 * <ul>
 *   <li>per client (the servlet remote address; behind a reverse proxy this must be the real client
 *       address, e.g. {@code server.forward-headers-strategy=native} with a trusted proxy), and</li>
 *   <li>aggregate for this instance, independent of client identity.</li>
 * </ul>
 *
 * Complements the existing four-request concurrency cap in {@link RailService}. It does not enforce a quota
 * shared across instances; that remains an ingress / provider-quota decision.
 */
final class RailRateLimiter {
    enum Decision { ALLOWED, CLIENT_LIMITED, AGGREGATE_LIMITED }

    /** Bounded memory: beyond this many distinct clients in one window only the aggregate limit applies. */
    static final int MAX_TRACKED_CLIENTS = 10_000;

    @ConfigurationProperties("life-in-uk.rail-rate-limit")
    record Limits(@DefaultValue("6") int perClientPerMinute, @DefaultValue("60") int aggregatePerMinute) {
        Limits {
            if (perClientPerMinute < 1 || perClientPerMinute > 120) {
                throw new IllegalArgumentException("Rail per-client-per-minute must be between 1 and 120");
            }
            if (aggregatePerMinute < perClientPerMinute || aggregatePerMinute > 600) {
                throw new IllegalArgumentException("Rail aggregate-per-minute must be between per-client limit and 600");
            }
        }
        static Limits defaults() { return new Limits(6, 60); }
    }

    private final Limits limits;
    private final Clock clock;
    private final Map<String, Integer> perClient = new HashMap<>();
    private long window = Long.MIN_VALUE;
    private int aggregate;

    RailRateLimiter(Limits limits, Clock clock) {
        this.limits = limits;
        this.clock = clock;
    }

    synchronized Decision tryAcquire(String client) {
        long minute = Math.floorDiv(clock.millis(), 60_000L);
        if (minute != window) {
            window = minute;
            aggregate = 0;
            perClient.clear();
        }
        if (aggregate >= limits.aggregatePerMinute()) {
            return Decision.AGGREGATE_LIMITED;
        }
        String key = client == null || client.isBlank() ? "unknown" : client;
        Integer used = perClient.get(key);
        if (used != null && used >= limits.perClientPerMinute()) {
            return Decision.CLIENT_LIMITED;
        }
        if (used != null || perClient.size() < MAX_TRACKED_CLIENTS) {
            perClient.put(key, used == null ? 1 : used + 1);
        }
        aggregate++;
        return Decision.ALLOWED;
    }

    /** Whole seconds until the current window ends, at least 1. */
    synchronized long retryAfterSeconds() {
        long remaining = 60_000L - Math.floorMod(clock.millis(), 60_000L);
        return Math.max(1, (remaining + 999) / 1000);
    }
}
