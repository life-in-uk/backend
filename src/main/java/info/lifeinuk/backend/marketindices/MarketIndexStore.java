package info.lifeinuk.backend.marketindices;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * SQL for V17. Observations are insert-only. Statements carry their own {@link #TIMEOUT} (a JDBC query timeout), as
 * the weather budget does, so a slow database cannot hold a request or retention thread indefinitely. Query plans at
 * representative volume are recorded in docs/market-indices.md.
 */
@Repository
class MarketIndexStore {
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    private final JdbcTemplate jdbc;

    @Autowired
    MarketIndexStore(JdbcTemplate jdbc) {
        this.jdbc = new JdbcTemplate(jdbc.getDataSource());
        this.jdbc.setQueryTimeout((int) TIMEOUT.toSeconds());
    }

    record StoredBatch(String source, String requestSha256) { }

    record StoredObservation(MarketIndexSymbol symbol, String source, Instant observedAt,
            MarketIndexSymbol.TimeSource timeSource, BigDecimal value, BigDecimal previousClose,
            Instant collectedAt, Instant receivedAt) { }

    record StoredHeartbeat(String source, MarketIndexPayloads.CollectorStatus status, Instant sentAt,
            Instant lastAttemptAt, Instant lastSuccessfulCollectionAt, Instant receivedAt) { }

    /** True if inserted; false if the batch id already exists (the caller compares the stored hash). */
    boolean insertBatch(MarketIndexPayloads.Batch batch, String credentialId) {
        return jdbc.update("""
                INSERT INTO market_index_ingest_batch (batch_id, source, collected_at, request_sha256, observation_count, credential_id)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (batch_id) DO NOTHING""",
                batch.batchId(), batch.source(), ts(batch.collectedAt()), batch.requestSha256(),
                batch.observations().size(), credentialId) == 1;
    }

    Optional<StoredBatch> batch(UUID batchId) {
        return jdbc.query("SELECT source, request_sha256 FROM market_index_ingest_batch WHERE batch_id = ?",
                (rs, n) -> new StoredBatch(rs.getString(1), rs.getString(2)), batchId).stream().findFirst();
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

    /**
     * The newest observation of one symbol and source with {@code observed_at <= notAfter}: a future-dated row cannot
     * displace a current one before its time. Written as a scalar max() so the planner always resolves it on the
     * (symbol, source, observed_at) natural-key index: an ORDER BY ... LIMIT 1 form can be planned as a backward scan
     * of the observed_at index that filters through every newer row of other sources (measured: 1.8 M rows, 159 ms
     * at two years' volume for a source that stopped a year earlier; docs/market-indices.md).
     */
    Optional<StoredObservation> latest(MarketIndexSymbol symbol, String source, Instant notAfter) {
        return jdbc.query(SELECT + " " + """
                WHERE o.symbol = ? AND o.source = ? AND o.observed_at = (
                    SELECT max(m.observed_at) FROM market_index_observation m
                    WHERE m.symbol = ? AND m.source = ? AND m.observed_at <= ?)""",
                MarketIndexStore::observation, symbol.name(), source, symbol.name(), source, ts(notAfter)).stream().findFirst();
    }

    private static final String SELECT = """
            SELECT o.symbol, o.source, o.observed_at, o.observed_at_source, o.value, o.previous_close, b.collected_at, o.received_at
            FROM market_index_observation o JOIN market_index_ingest_batch b ON b.batch_id = o.batch_id""";

    private static StoredObservation observation(ResultSet rs, int row) throws SQLException {
        return new StoredObservation(MarketIndexSymbol.valueOf(rs.getString(1)), rs.getString(2), instant(rs, 3),
                MarketIndexSymbol.TimeSource.valueOf(rs.getString(4)), rs.getBigDecimal(5), rs.getBigDecimal(6),
                instant(rs, 7), instant(rs, 8));
    }

    /**
     * Records a heartbeat. Liveness ({@code received_at}) is always refreshed; the reported status fields are replaced
     * only by a report with a later {@code sent_at}. Returns true if this report's status was applied.
     */
    boolean upsertHeartbeat(MarketIndexPayloads.Heartbeat h, String credentialId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                INSERT INTO market_index_collector_heartbeat AS c (source, credential_id, collector_status, provider_failure_code,
                    sent_at, last_attempt_at, last_successful_collection_at, pending_batches, oldest_pending_collected_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source) DO UPDATE SET
                    received_at = clock_timestamp(),
                    credential_id = CASE WHEN EXCLUDED.sent_at > c.sent_at THEN EXCLUDED.credential_id ELSE c.credential_id END,
                    collector_status = CASE WHEN EXCLUDED.sent_at > c.sent_at THEN EXCLUDED.collector_status ELSE c.collector_status END,
                    provider_failure_code = CASE WHEN EXCLUDED.sent_at > c.sent_at THEN EXCLUDED.provider_failure_code ELSE c.provider_failure_code END,
                    last_attempt_at = CASE WHEN EXCLUDED.sent_at > c.sent_at THEN EXCLUDED.last_attempt_at ELSE c.last_attempt_at END,
                    last_successful_collection_at = CASE WHEN EXCLUDED.sent_at > c.sent_at THEN EXCLUDED.last_successful_collection_at ELSE c.last_successful_collection_at END,
                    pending_batches = CASE WHEN EXCLUDED.sent_at > c.sent_at THEN EXCLUDED.pending_batches ELSE c.pending_batches END,
                    oldest_pending_collected_at = CASE WHEN EXCLUDED.sent_at > c.sent_at THEN EXCLUDED.oldest_pending_collected_at ELSE c.oldest_pending_collected_at END,
                    sent_at = GREATEST(EXCLUDED.sent_at, c.sent_at)
                RETURNING sent_at = ?""", Boolean.class,
                h.source(), credentialId, h.status().name(), h.providerFailureCode(), ts(h.sentAt()), ts(h.lastAttemptAt()),
                ts(h.lastSuccessfulCollectionAt()), h.pendingBatches(), ts(h.oldestPendingCollectedAt()), ts(h.sentAt())));
    }

    Optional<StoredHeartbeat> heartbeat(String source) {
        return jdbc.query("""
                SELECT source, collector_status, sent_at, last_attempt_at, last_successful_collection_at, received_at
                FROM market_index_collector_heartbeat WHERE source = ?""",
                (rs, n) -> new StoredHeartbeat(rs.getString(1), MarketIndexPayloads.CollectorStatus.valueOf(rs.getString(2)),
                        instant(rs, 3), instant(rs, 4), instant(rs, 5), instant(rs, 6)), source).stream().findFirst();
    }

    /**
     * Deletes at most {@code limit} observations older than {@code cutoff}, oldest first, never the newest row of any
     * symbol and source. Uses the observed_at index for the scan and the natural-key index for the "newer row" probe.
     */
    int deleteObservationsBefore(Instant cutoff, int limit) {
        return jdbc.update("""
                DELETE FROM market_index_observation WHERE id IN (
                    SELECT o.id FROM market_index_observation o
                    WHERE o.observed_at < ?
                      AND EXISTS (SELECT 1 FROM market_index_observation n
                                  WHERE n.symbol = o.symbol AND n.source = o.source AND n.observed_at > o.observed_at)
                    ORDER BY o.observed_at LIMIT ?)""", ts(cutoff), limit);
    }

    /** Deletes at most {@code limit} batches received before {@code cutoff} that no observation references. */
    int deleteOrphanBatchesBefore(Instant cutoff, int limit) {
        return jdbc.update("""
                DELETE FROM market_index_ingest_batch WHERE batch_id IN (
                    SELECT b.batch_id FROM market_index_ingest_batch b
                    WHERE b.received_at < ?
                      AND NOT EXISTS (SELECT 1 FROM market_index_observation o WHERE o.batch_id = b.batch_id)
                    ORDER BY b.received_at LIMIT ?)""", ts(cutoff), limit);
    }

    private static Timestamp ts(Instant instant) { return instant == null ? null : Timestamp.from(instant); }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
