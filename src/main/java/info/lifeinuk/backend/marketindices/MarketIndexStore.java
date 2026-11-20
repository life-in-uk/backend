package info.lifeinuk.backend.marketindices;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * SQL for V17. Observations are insert-only. Statements carry their own {@link #TIMEOUT} (a JDBC query timeout), as
 * the weather budget does, so a slow database cannot hold a request thread indefinitely.
 */
@Repository
class MarketIndexStore {
    static final Duration TIMEOUT = Duration.ofSeconds(2);
    private final JdbcTemplate jdbc;

    @Autowired
    MarketIndexStore(JdbcTemplate jdbc) {
        this.jdbc = new JdbcTemplate(jdbc.getDataSource());
        this.jdbc.setQueryTimeout((int) TIMEOUT.toSeconds());
    }

    record StoredBatch(String requestSha256) { }

    record StoredObservation(MarketIndexSymbol symbol, String source, Instant observedAt,
            MarketIndexSymbol.TimeSource timeSource, BigDecimal value, BigDecimal previousClose,
            Instant collectedAt, Instant receivedAt) { }

    record StoredHeartbeat(String source, MarketIndexPayloads.CollectorStatus status, Instant sentAt, Instant receivedAt) { }

    /** True if inserted; false if the batch id already exists (the caller compares the stored hash). */
    boolean insertBatch(MarketIndexPayloads.Batch batch, String credentialId) {
        return jdbc.update("""
                INSERT INTO market_index_ingest_batch (batch_id, source, collected_at, request_sha256, observation_count, credential_id)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (batch_id) DO NOTHING""",
                batch.batchId(), batch.source(), ts(batch.collectedAt()), batch.requestSha256(),
                batch.observations().size(), credentialId) == 1;
    }

    Optional<StoredBatch> batch(UUID batchId) {
        return jdbc.query("SELECT request_sha256 FROM market_index_ingest_batch WHERE batch_id = ?",
                (rs, n) -> new StoredBatch(rs.getString(1)), batchId).stream().findFirst();
    }

    /** True if inserted; false if (symbol, source, observedAt) already exists. */
    boolean insertObservation(UUID batchId, String source, MarketIndexPayloads.Observation o) {
        return jdbc.update("""
                INSERT INTO market_index_observation (symbol, source, observed_at, observed_at_source, value, previous_close, batch_id)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT ON CONSTRAINT market_index_observation_natural DO NOTHING""",
                o.symbol().name(), source, ts(o.observedAt()), o.timeSource().name(), o.value(), o.previousClose(), batchId) == 1;
    }

    Optional<StoredObservation> observation(MarketIndexSymbol symbol, String source, Instant observedAt) {
        return jdbc.query(SELECT + " WHERE o.symbol = ? AND o.source = ? AND o.observed_at = ?",
                MarketIndexStore::observation, symbol.name(), source, ts(observedAt)).stream().findFirst();
    }

    /** Per symbol, the row with the greatest observed_at among {@code sources}; arrival order never matters. */
    Optional<StoredObservation> latest(MarketIndexSymbol symbol, List<String> sources) {
        if (sources.isEmpty()) return Optional.empty();
        return jdbc.query(SELECT + " WHERE o.symbol = ? AND o.source = ANY (?) ORDER BY o.observed_at DESC, o.id DESC LIMIT 1",
                ps -> {
                    ps.setString(1, symbol.name());
                    ps.setArray(2, ps.getConnection().createArrayOf("text", sources.toArray()));
                },
                MarketIndexStore::observation).stream().findFirst();
    }

    private static final String SELECT = """
            SELECT o.symbol, o.source, o.observed_at, o.observed_at_source, o.value, o.previous_close, b.collected_at, o.received_at
            FROM market_index_observation o JOIN market_index_ingest_batch b ON b.batch_id = o.batch_id""";

    private static StoredObservation observation(ResultSet rs, int row) throws SQLException {
        return new StoredObservation(MarketIndexSymbol.valueOf(rs.getString(1)), rs.getString(2), instant(rs, 3),
                MarketIndexSymbol.TimeSource.valueOf(rs.getString(4)), rs.getBigDecimal(5), rs.getBigDecimal(6),
                instant(rs, 7), instant(rs, 8));
    }

    /** Keeps the newest report per source by the collector's own sentAt; true if this report was applied. */
    boolean upsertHeartbeat(MarketIndexPayloads.Heartbeat h, String credentialId) {
        return jdbc.update("""
                INSERT INTO market_index_collector_heartbeat AS c (source, credential_id, collector_status, provider_failure_code,
                    sent_at, last_attempt_at, last_successful_collection_at, pending_batches, oldest_pending_collected_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source) DO UPDATE SET credential_id = EXCLUDED.credential_id,
                    collector_status = EXCLUDED.collector_status, provider_failure_code = EXCLUDED.provider_failure_code,
                    sent_at = EXCLUDED.sent_at, last_attempt_at = EXCLUDED.last_attempt_at,
                    last_successful_collection_at = EXCLUDED.last_successful_collection_at,
                    pending_batches = EXCLUDED.pending_batches,
                    oldest_pending_collected_at = EXCLUDED.oldest_pending_collected_at, received_at = clock_timestamp()
                WHERE EXCLUDED.sent_at > c.sent_at""",
                h.source(), credentialId, h.status().name(), h.providerFailureCode(), ts(h.sentAt()), ts(h.lastAttemptAt()),
                ts(h.lastSuccessfulCollectionAt()), h.pendingBatches(), ts(h.oldestPendingCollectedAt())) == 1;
    }

    /** The most recently received heartbeat among {@code sources}. */
    Optional<StoredHeartbeat> latestHeartbeat(List<String> sources) {
        if (sources.isEmpty()) return Optional.empty();
        return jdbc.query("""
                SELECT source, collector_status, sent_at, received_at FROM market_index_collector_heartbeat
                WHERE source = ANY (?) ORDER BY received_at DESC LIMIT 1""",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", sources.toArray())),
                (rs, n) -> new StoredHeartbeat(rs.getString(1), MarketIndexPayloads.CollectorStatus.valueOf(rs.getString(2)),
                        instant(rs, 3), instant(rs, 4))).stream().findFirst();
    }

    /**
     * Deletes up to {@code limit} observations older than {@code cutoff}, never the newest row of any symbol and
     * source, then batches left without observations. Returns the number of observations deleted.
     */
    int purgeBefore(Instant cutoff, int limit) {
        int deleted = jdbc.update("""
                DELETE FROM market_index_observation WHERE id IN (
                    SELECT o.id FROM market_index_observation o
                    WHERE o.observed_at < ?
                      AND EXISTS (SELECT 1 FROM market_index_observation n
                                  WHERE n.symbol = o.symbol AND n.source = o.source AND n.observed_at > o.observed_at)
                    ORDER BY o.observed_at LIMIT ?)""", ts(cutoff), limit);
        jdbc.update("""
                DELETE FROM market_index_ingest_batch b WHERE b.received_at < ?
                  AND NOT EXISTS (SELECT 1 FROM market_index_observation o WHERE o.batch_id = b.batch_id)""", ts(cutoff));
        return deleted;
    }

    private static Timestamp ts(Instant instant) { return instant == null ? null : Timestamp.from(instant); }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        var value = rs.getObject(column, java.time.OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
