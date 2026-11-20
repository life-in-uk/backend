package info.lifeinuk.backend.marketindices;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ingestion (one transaction per batch: all observations stored, or none) and the public latest-value view.
 * Retention runs at most hourly after a successful batch, in its own transaction, so it never affects an
 * acknowledgement.
 */
@Service
class MarketIndexService {
    private static final Logger log = LoggerFactory.getLogger(MarketIndexService.class);
    static final Duration PURGE_INTERVAL = Duration.ofHours(1);
    static final int PURGE_LIMIT = 5000;

    private final MarketIndexStore store;
    private final MarketIndexProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final AtomicReference<Instant> lastPurge = new AtomicReference<>(Instant.EPOCH);

    @Autowired
    MarketIndexService(MarketIndexStore store, MarketIndexProperties properties, PlatformTransactionManager transactions) {
        this(store, properties, transactions, Clock.systemUTC());
    }

    MarketIndexService(MarketIndexStore store, MarketIndexProperties properties, PlatformTransactionManager transactions, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setTimeout(10);
        this.clock = clock;
    }

    Instant now() { return clock.instant(); }

    MarketIndexProperties properties() { return properties; }

    // ---------------------------------------------------------------- ingestion

    record ObservationResult(String symbol, Instant observedAt, String result) { }

    record Acknowledgement(String status, UUID batchId, boolean replay, List<ObservationResult> observations) { }

    Acknowledgement ingest(MarketIndexPayloads.Batch batch, String credentialId) {
        Acknowledgement ack = tx.execute(status -> {
            var results = new ArrayList<ObservationResult>();
            if (!store.insertBatch(batch, credentialId)) {
                var existing = store.batch(batch.batchId()).orElseThrow(() -> new IllegalStateException("Batch vanished"));
                if (!existing.requestSha256().equals(batch.requestSha256())) {
                    throw new MarketIndexPayloads.Rejected(HttpStatus.CONFLICT, "BATCH_ID_CONFLICT",
                            "batchId was already used with different content.");
                }
                batch.observations().forEach(o -> results.add(new ObservationResult(o.symbol().name(), o.observedAt(), "ALREADY_ACCEPTED")));
                return new Acknowledgement("ACCEPTED", batch.batchId(), true, List.copyOf(results));
            }
            for (var o : batch.observations()) {
                if (store.insertObservation(batch.batchId(), batch.source(), o)) {
                    results.add(new ObservationResult(o.symbol().name(), o.observedAt(), "STORED"));
                    continue;
                }
                var stored = store.observation(o.symbol(), batch.source(), o.observedAt()).orElseThrow(() -> new IllegalStateException("Observation vanished"));
                if (!same(stored, o)) {
                    // Rolls back the whole batch: a source never reports two different values for one instant.
                    throw new MarketIndexPayloads.Rejected(HttpStatus.CONFLICT, "OBSERVATION_CONFLICT",
                            "observations[" + o.symbol() + "] differs from the value already stored for that symbol, source and observedAt.");
                }
                results.add(new ObservationResult(o.symbol().name(), o.observedAt(), "DUPLICATE"));
            }
            return new Acknowledgement("ACCEPTED", batch.batchId(), false, List.copyOf(results));
        });
        Objects.requireNonNull(ack);
        long stored = ack.observations().stream().filter(r -> r.result().equals("STORED")).count();
        Duration lag = batch.observations().stream().map(o -> Duration.between(o.observedAt(), now()))
                .max(Duration::compareTo).orElse(Duration.ZERO);
        log.info("Market index batch {} source={} key={} replay={} stored={} duplicate={} oldestObservationAgeSeconds={}",
                batch.batchId(), batch.source(), credentialId, ack.replay(), stored,
                ack.replay() ? 0 : ack.observations().size() - stored, lag.toSeconds());
        purgeIfDue();
        return ack;
    }

    private static boolean same(MarketIndexStore.StoredObservation stored, MarketIndexPayloads.Observation o) {
        return stored.timeSource() == o.timeSource() && stored.value().compareTo(o.value()) == 0
                && (stored.previousClose() == null ? o.previousClose() == null
                        : o.previousClose() != null && stored.previousClose().compareTo(o.previousClose()) == 0);
    }

    void purgeIfDue() {
        Instant now = now();
        Instant previous = lastPurge.get();
        if (Duration.between(previous, now).compareTo(PURGE_INTERVAL) < 0 || !lastPurge.compareAndSet(previous, now)) return;
        try {
            Integer deleted = tx.execute(status -> store.purgeBefore(now.minus(properties.retention()), PURGE_LIMIT));
            if (deleted != null && deleted > 0) log.info("Market index retention removed {} observations older than {}", deleted, properties.retention());
        } catch (RuntimeException failure) {
            log.warn("Market index retention failed ({}); will retry later", failure.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------------- heartbeat

    record HeartbeatAck(String status, boolean applied) { }

    HeartbeatAck heartbeat(MarketIndexPayloads.Heartbeat heartbeat, String credentialId) {
        boolean applied = Boolean.TRUE.equals(tx.execute(status -> store.upsertHeartbeat(heartbeat, credentialId)));
        if (heartbeat.status() == MarketIndexPayloads.CollectorStatus.PROVIDER_FAILED) {
            log.warn("Market index collector source={} reports provider failure {}", heartbeat.source(), heartbeat.providerFailureCode());
        }
        return new HeartbeatAck("ACCEPTED", applied);
    }

    // ---------------------------------------------------------------- public view

    record Provenance(Instant observedAt, String observedAtSource, Instant collectedAt, Instant receivedAt) { }

    record Market(String exchange, String timeZone, String session, LocalDate calendarCoveredThrough) { }

    record Freshness(String status, Long ageSeconds, String basis, long freshWithinSeconds, long staleAfterSeconds) { }

    record Index(String symbol, String name, String value, String previousClose, String change, String changePercent,
            String source, Provenance provenance, Market market, Freshness freshness) { }

    record Collector(String status, String basis, long heartbeatTimeoutSeconds) { }

    record Latest(Instant generatedAt, Collector collector, List<Index> indices, String attribution) { }

    enum CollectorState { HEALTHY, PROVIDER_FAILED, DISCONNECTED, UNKNOWN }

    Latest latest() {
        Instant now = now();
        var sources = properties.publicSources();
        var indices = new ArrayList<Index>();
        for (var symbol : MarketIndexSymbol.values()) {
            var session = symbol.exchange.session(now);
            var market = new Market(symbol.exchange.name(), symbol.exchange.zone.getId(), session.state().name(),
                    symbol.exchange.coveredThrough);
            var row = store.latest(symbol, sources).orElse(null);
            var freshness = MarketIndexFreshness.evaluate(row == null ? null : row.observedAt(),
                    row == null ? null : row.timeSource(), session, now, properties);
            var fresh = new Freshness(freshness.status().name(), freshness.ageSeconds(), "observedAt",
                    properties.freshWithin().toSeconds(), properties.staleAfter().toSeconds());
            if (row == null) {
                indices.add(new Index(symbol.name(), symbol.displayName, null, null, null, null, null,
                        new Provenance(null, null, null, null), market, fresh));
                continue;
            }
            String change = null;
            String percent = null;
            if (row.previousClose() != null) {
                BigDecimal difference = row.value().subtract(row.previousClose());
                change = difference.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
                percent = difference.multiply(BigDecimal.valueOf(100))
                        .divide(row.previousClose(), 2, RoundingMode.HALF_EVEN).toPlainString();
            }
            indices.add(new Index(symbol.name(), symbol.displayName, row.value().toPlainString(),
                    row.previousClose() == null ? null : row.previousClose().toPlainString(), change, percent, row.source(),
                    new Provenance(row.observedAt(), row.timeSource().name(), row.collectedAt(), row.receivedAt()), market, fresh));
        }
        return new Latest(now, collector(now), List.copyOf(indices), properties.attribution());
    }

    /** Liveness from the heartbeat's receipt time (the one legitimate use of a server receipt time here). */
    private Collector collector(Instant now) {
        var heartbeat = store.latestHeartbeat(properties.publicSources()).orElse(null);
        CollectorState state;
        if (heartbeat == null) {
            state = CollectorState.UNKNOWN;
        } else if (Duration.between(heartbeat.receivedAt(), now).compareTo(properties.heartbeatTimeout()) > 0) {
            state = CollectorState.DISCONNECTED;
        } else {
            state = heartbeat.status() == MarketIndexPayloads.CollectorStatus.OK ? CollectorState.HEALTHY : CollectorState.PROVIDER_FAILED;
        }
        return new Collector(state.name(), "heartbeatReceivedAt", properties.heartbeatTimeout().toSeconds());
    }
}
