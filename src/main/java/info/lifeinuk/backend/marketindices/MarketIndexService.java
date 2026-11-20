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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ingestion (one transaction per batch: all observations stored, or none), heartbeats and the public latest-value
 * view. Retention runs separately ({@link MarketIndexRetention}) and never on the acknowledgement path.
 */
@Service
class MarketIndexService {
    private static final Logger log = LoggerFactory.getLogger(MarketIndexService.class);

    private final MarketIndexStore store;
    private final MarketIndexProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

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

    /**
     * Authorization first (the key must be bound to the batch's source), then: an identical retry of a committed batch
     * is acknowledged as a replay regardless of the time window (it was accepted when it arrived); a new batch must be
     * within the window and is stored atomically.
     */
    Acknowledgement ingest(MarketIndexPayloads.Batch batch, MarketIndexIngestSecurity.Key key) {
        MarketIndexPayloads.requireAuthorized(batch.source(), key.sources(), properties);
        Acknowledgement ack = tx.execute(status -> {
            var existing = store.batch(batch.batchId());
            if (existing.isPresent()) return replay(batch, existing.get());
            MarketIndexPayloads.requireWithinWindow(batch, now(), properties);
            if (!store.insertBatch(batch, key.id())) {
                // A concurrent identical request committed first.
                return replay(batch, store.batch(batch.batchId()).orElseThrow(() -> new IllegalStateException("Batch vanished")));
            }
            var results = new ArrayList<ObservationResult>();
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
                batch.batchId(), batch.source(), key.id(), ack.replay(), stored,
                ack.replay() ? 0 : ack.observations().size() - stored, lag.toSeconds());
        return ack;
    }

    private static Acknowledgement replay(MarketIndexPayloads.Batch batch, MarketIndexStore.StoredBatch existing) {
        if (!existing.requestSha256().equals(batch.requestSha256())) {
            throw new MarketIndexPayloads.Rejected(HttpStatus.CONFLICT, "BATCH_ID_CONFLICT", "batchId was already used with different content.");
        }
        var results = batch.observations().stream()
                .map(o -> new ObservationResult(o.symbol().name(), o.observedAt(), "ALREADY_ACCEPTED")).toList();
        return new Acknowledgement("ACCEPTED", batch.batchId(), true, results);
    }

    private static boolean same(MarketIndexStore.StoredObservation stored, MarketIndexPayloads.Observation o) {
        return stored.timeSource() == o.timeSource() && stored.value().compareTo(o.value()) == 0
                && (stored.previousClose() == null ? o.previousClose() == null
                        : o.previousClose() != null && stored.previousClose().compareTo(o.previousClose()) == 0);
    }

    // ---------------------------------------------------------------- heartbeat

    record HeartbeatAck(String status, boolean applied) { }

    HeartbeatAck heartbeat(MarketIndexPayloads.Heartbeat heartbeat, MarketIndexIngestSecurity.Key key) {
        MarketIndexPayloads.requireAuthorized(heartbeat.source(), key.sources(), properties);
        boolean applied = Boolean.TRUE.equals(tx.execute(status -> store.upsertHeartbeat(heartbeat, key.id())));
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
            String source, String collectorStatus, Provenance provenance, Market market, Freshness freshness) { }

    record SourceStatus(String source, String status) { }

    record Collector(String status, String basis, long heartbeatTimeoutSeconds, List<SourceStatus> sources) { }

    record Latest(Instant generatedAt, Collector collector, List<Index> indices, String attribution) { }

    /** Ordered by severity: the aggregate is the worst state of any public source, so no source can mask another. */
    enum CollectorState { HEALTHY, PROVIDER_FAILED, COLLECTION_STALLED, NO_SUCCESSFUL_COLLECTION, UNKNOWN, DISCONNECTED }

    /**
     * One source's collector state. Liveness compares the server's receipt time with the server clock; the
     * collection checks compare collector-reported times only with each other (never with server time), so collector
     * clock skew cannot turn a failure into HEALTHY or a live collector into DISCONNECTED.
     */
    static CollectorState state(MarketIndexStore.StoredHeartbeat heartbeat, Instant now, Duration timeout) {
        if (heartbeat == null) return CollectorState.UNKNOWN;
        if (Duration.between(heartbeat.receivedAt(), now).compareTo(timeout) > 0) return CollectorState.DISCONNECTED;
        if (heartbeat.lastSuccessfulCollectionAt() == null) return CollectorState.NO_SUCCESSFUL_COLLECTION;
        if (heartbeat.status() != MarketIndexPayloads.CollectorStatus.OK
                || heartbeat.lastSuccessfulCollectionAt().isBefore(heartbeat.lastAttemptAt())) {
            return CollectorState.PROVIDER_FAILED;
        }
        if (Duration.between(heartbeat.lastAttemptAt(), heartbeat.sentAt()).compareTo(timeout) > 0) {
            return CollectorState.COLLECTION_STALLED;
        }
        return CollectorState.HEALTHY;
    }

    Latest latest() {
        Instant now = now();
        var sources = properties.publicSources();
        var states = new java.util.LinkedHashMap<String, CollectorState>();
        for (String source : sources) {
            states.put(source, state(store.heartbeat(source).orElse(null), now, properties.heartbeatTimeout()));
        }
        var aggregate = states.values().stream().max(Enum::compareTo).orElse(CollectorState.UNKNOWN);
        var collector = new Collector(aggregate.name(), "heartbeatReceivedAt", properties.heartbeatTimeout().toSeconds(),
                states.entrySet().stream().map(e -> new SourceStatus(e.getKey(), e.getValue().name())).toList());
        var indices = new ArrayList<Index>();
        for (var symbol : MarketIndexSymbol.values()) {
            var session = symbol.exchange.session(now);
            var market = new Market(symbol.exchange.name(), symbol.exchange.zone.getId(), session.state().name(),
                    symbol.exchange.coveredThrough);
            // Newest per public source, never later than now; then the newest of those.
            MarketIndexStore.StoredObservation row = null;
            for (String source : sources) {
                var candidate = store.latest(symbol, source, now).orElse(null);
                if (candidate != null && (row == null || candidate.observedAt().isAfter(row.observedAt()))) row = candidate;
            }
            var freshness = MarketIndexFreshness.evaluate(row == null ? null : row.observedAt(),
                    row == null ? null : row.timeSource(), session, now, properties);
            var fresh = new Freshness(freshness.status().name(), freshness.ageSeconds(), "observedAt",
                    properties.freshWithin().toSeconds(), properties.staleAfter().toSeconds());
            if (row == null) {
                indices.add(new Index(symbol.name(), symbol.displayName, null, null, null, null, null, null,
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
                    states.get(row.source()).name(),
                    new Provenance(row.observedAt(), row.timeSource().name(), row.collectedAt(), row.receivedAt()), market, fresh));
        }
        return new Latest(now, collector, List.copyOf(indices), properties.attribution());
    }
}
