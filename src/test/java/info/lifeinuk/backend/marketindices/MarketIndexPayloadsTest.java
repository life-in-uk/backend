package info.lifeinuk.backend.marketindices;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class MarketIndexPayloadsTest {
    static final Instant NOW = Instant.parse("2026-11-20T14:33:00Z");
    static final MarketIndexProperties PROPS = MarketIndexPropertiesTest.with("", List.of("mock", "provider-x"), false, List.of(),
            Duration.ofDays(30), Duration.ofDays(730));
    static final String BATCH = "6f1c2a8e-4b8f-4d0e-9a51-2f4b7d1c9e03";

    static String batch(String source, String collectedAt, String observations) {
        return "{\"schemaVersion\":1,\"batchId\":\"" + BATCH + "\",\"source\":\"" + source + "\",\"collectedAt\":\""
                + collectedAt + "\",\"observations\":[" + observations + "]}";
    }

    static String obs(String symbol, String value, String observedAt, String timeSource) {
        return "{\"symbol\":\"" + symbol + "\",\"value\":\"" + value + "\",\"observedAt\":\"" + observedAt
                + "\",\"observedAtSource\":\"" + timeSource + "\"}";
    }

    static final java.util.Set<String> KEY_SOURCES = java.util.Set.of("mock", "provider-x");

    /** The controller/service order: parse, authorize the source for the key, then the time window for a new batch. */
    static MarketIndexPayloads.Batch full(byte[] body) {
        var batch = MarketIndexPayloads.batch(body);
        MarketIndexPayloads.requireAuthorized(batch.source(), KEY_SOURCES, PROPS);
        MarketIndexPayloads.requireWithinWindow(batch, NOW, PROPS);
        return batch;
    }

    static MarketIndexPayloads.Rejected reject(String body) {
        return catchThrowableOfType(MarketIndexPayloads.Rejected.class, () -> full(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void validBatchIsNormalisedAndSorted() {
        var batch = full(batch("provider-x", "2026-11-20T14:32:05.214+00:00",
                obs("SPX", "5987.21", "2026-11-20T14:16:30Z", "EXCHANGE") + ","
                        + "{\"symbol\":\"UKX\",\"value\":\"8123.45\",\"previousClose\":\"8100.12\",\"observedAt\":\"2026-11-20T09:15:00-05:00\",\"observedAtSource\":\"PROVIDER\"}")
                .getBytes(StandardCharsets.UTF_8));
        assertThat(batch.batchId()).isEqualTo(UUID.fromString(BATCH));
        assertThat(batch.observations()).extracting(MarketIndexPayloads.Observation::symbol)
                .containsExactly(MarketIndexSymbol.UKX, MarketIndexSymbol.SPX);
        var ukx = batch.observations().getFirst();
        assertThat(ukx.observedAt()).isEqualTo(Instant.parse("2026-11-20T14:15:00Z"));
        assertThat(ukx.value().toPlainString()).isEqualTo("8123.4500");
        assertThat(ukx.previousClose().toPlainString()).isEqualTo("8100.1200");
        assertThat(batch.requestSha256()).hasSize(64);
    }

    @Test
    void hashIgnoresKeyOrderWhitespaceAndEquivalentSpellings() {
        var a = full(batch("provider-x", "2026-11-20T14:32:05Z",
                obs("UKX", "8123.45", "2026-11-20T14:15:00Z", "PROVIDER")).getBytes(StandardCharsets.UTF_8));
        String reordered = "{ \"observations\": [ {\"observedAtSource\":\"PROVIDER\",\"observedAt\":\"2026-11-20T14:15:00.000+00:00\","
                + "\"value\":\"8123.4500\",\"symbol\":\"UKX\"} ], \"collectedAt\":\"2026-11-20T14:32:05.000Z\","
                + "\"source\":\"provider-x\", \"batchId\":\"" + BATCH + "\", \"schemaVersion\": 1 }";
        var b = full(reordered.getBytes(StandardCharsets.UTF_8));
        assertThat(b.requestSha256()).isEqualTo(a.requestSha256());
        var c = full(batch("provider-x", "2026-11-20T14:32:05Z",
                obs("UKX", "8123.46", "2026-11-20T14:15:00Z", "PROVIDER")).getBytes(StandardCharsets.UTF_8));
        assertThat(c.requestSha256()).isNotEqualTo(a.requestSha256());
    }

    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "-1", "0", "0.0000", "1e5", "8123.45678", "123456789", "8,123.45", " 8123", ""})
    void invalidDecimalsAreRejected(String value) {
        var rejected = reject(batch("provider-x", "2026-11-20T14:32:05Z", obs("UKX", value, "2026-11-20T14:15:00Z", "PROVIDER")));
        assertThat(rejected.code).isEqualTo("MARKET_BATCH_INVALID");
        assertThat(rejected.getMessage()).contains("observations[0].value");
    }

    @Test
    void numericJsonValueIsRejected() {
        String body = batch("provider-x", "2026-11-20T14:32:05Z", "{\"symbol\":\"UKX\",\"value\":8123.45,\"observedAt\":\"2026-11-20T14:15:00Z\",\"observedAtSource\":\"PROVIDER\"}");
        assertThat(reject(body).code).isEqualTo("MARKET_BATCH_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-20T14:15:00", "2026-11-20 14:15:00Z", "2026-11-20T14:15:00.1234567Z", "2026-02-30T10:00:00Z", "yesterday"})
    void timestampsNeedAnOffsetAndAtMostMicroseconds(String observedAt) {
        assertThat(reject(batch("provider-x", "2026-11-20T14:32:05Z", obs("UKX", "8123.45", observedAt, "PROVIDER"))).code)
                .isEqualTo("MARKET_BATCH_INVALID");
    }

    @Test
    void structuralRules() {
        String ok = obs("UKX", "1", "2026-11-20T14:15:00Z", "PROVIDER");
        assertThat(reject("[]").code).isEqualTo("MARKET_BATCH_INVALID");
        assertThat(reject("{").code).isEqualTo("MARKET_BATCH_INVALID");
        assertThat(reject(batch("provider-x", "2026-11-20T14:32:05Z", "")).getMessage()).contains("1 to 6");
        assertThat(reject(batch("provider-x", "2026-11-20T14:32:05Z", ok + "," + ok)).getMessage()).contains("repeated");
        assertThat(reject(batch("provider-x", "2026-11-20T14:32:05Z", obs("FTMC", "1", "2026-11-20T14:15:00Z", "PROVIDER"))).getMessage()).contains("symbol");
        assertThat(reject(batch("provider-x", "2026-11-20T14:32:05Z", obs("UKX", "1", "2026-11-20T14:15:00Z", "MARKET"))).getMessage()).contains("observedAtSource");
        assertThat(reject(batch("Provider", "2026-11-20T14:32:05Z", ok)).getMessage()).contains("source");
        assertThat(reject(batch("provider-x", "2026-11-20T14:32:05Z", ok).replace("\"schemaVersion\":1", "\"schemaVersion\":2")).getMessage()).contains("schemaVersion");
        assertThat(reject(batch("provider-x", "2026-11-20T14:32:05Z", ok).replace(BATCH, BATCH.toUpperCase())).getMessage()).contains("batchId");
        assertThat(reject(batch("provider-x", "2026-11-20T14:32:05Z", ok).replace("{\"schemaVersion\":1,", "{\"schemaVersion\":1,\"extra\":true,")).getMessage()).contains("extra");
        assertThat(reject(batch("provider-x", "2026-11-20T14:32:05Z", ok).replace("{\"schemaVersion\":1,", "{\"schemaVersion\":1,\"schemaVersion\":1,")).code).isEqualTo("MARKET_BATCH_INVALID");
        assertThat(reject(batch("provider-x", "2026-11-20T14:32:05Z", ok) + "{}").code).isEqualTo("MARKET_BATCH_INVALID");
    }

    @Test
    void sourceMustBeAcceptedAndBoundToTheKey() {
        var batch = MarketIndexPayloads.batch(batch("provider-x", "2026-11-20T14:32:05Z",
                obs("UKX", "1", "2026-11-20T14:15:00Z", "PROVIDER")).getBytes(StandardCharsets.UTF_8));
        var forbidden = catchThrowableOfType(MarketIndexPayloads.Rejected.class,
                () -> MarketIndexPayloads.requireAuthorized(batch.source(), java.util.Set.of("mock"), PROPS));
        assertThat(forbidden.status.value()).isEqualTo(403);
        assertThat(forbidden.code).isEqualTo("SOURCE_NOT_AUTHORIZED");
        var unaccepted = catchThrowableOfType(MarketIndexPayloads.Rejected.class,
                () -> MarketIndexPayloads.requireAuthorized("provider-y", java.util.Set.of("provider-y"), PROPS));
        assertThat(unaccepted.code).isEqualTo("SOURCE_NOT_ACCEPTED");
    }

    @Test
    void semanticRulesAre422() {
        assertThat(reject(batch("provider-y", "2026-11-20T14:32:05Z", obs("UKX", "1", "2026-11-20T14:15:00Z", "PROVIDER"))).code)
                .isEqualTo("SOURCE_NOT_ACCEPTED");
        assertThat(reject(batch("mock", "2026-11-20T14:32:05Z", obs("UKX", "1", "2026-11-20T14:15:00Z", "EXCHANGE"))).code)
                .isEqualTo("MOCK_TIME_SOURCE_INVALID");
        assertThat(reject(batch("provider-x", "2026-11-20T14:39:00Z", obs("UKX", "1", "2026-11-20T14:15:00Z", "PROVIDER"))).code)
                .isEqualTo("COLLECTOR_CLOCK_SKEW");
        assertThat(reject(batch("provider-x", "2026-11-20T14:37:00Z", obs("UKX", "1", "2026-11-20T14:35:30Z", "PROVIDER"))).code)
                .isEqualTo("FUTURE_OBSERVATION");
        assertThat(reject(batch("provider-x", "2026-11-20T14:00:00Z", obs("UKX", "1", "2026-11-20T14:15:00Z", "PROVIDER"))).code)
                .isEqualTo("OBSERVATION_AFTER_COLLECTION");
        assertThat(reject(batch("provider-x", "2026-11-20T14:00:00Z", obs("UKX", "1", "2026-10-20T14:32:59Z", "PROVIDER"))).code)
                .isEqualTo("OBSERVATION_TOO_OLD");
        var withinBackfill = full(batch("provider-x", "2026-11-20T14:00:00Z",
                obs("UKX", "1", "2026-10-21T14:34:00Z", "PROVIDER")).getBytes(StandardCharsets.UTF_8));
        assertThat(withinBackfill.observations()).hasSize(1);
    }

    static MarketIndexPayloads.Rejected rejectHeartbeat(String body) {
        return catchThrowableOfType(MarketIndexPayloads.Rejected.class,
                () -> MarketIndexPayloads.heartbeat(body.getBytes(StandardCharsets.UTF_8), NOW, PROPS));
    }

    static String heartbeat(String status, String failure, String sentAt) {
        return "{\"schemaVersion\":1,\"source\":\"mock\",\"sentAt\":\"" + sentAt + "\",\"collectorStatus\":\"" + status + "\","
                + (failure == null ? "" : "\"providerFailureCode\":\"" + failure + "\",")
                + ("OK".equals(status) ? "\"lastSuccessfulCollectionAt\":\"2026-11-20T14:31:00Z\"," : "")
                + "\"lastAttemptAt\":\"2026-11-20T14:31:00Z\",\"pendingBatches\":0}";
    }

    @Test
    void heartbeatRules() {
        var ok = MarketIndexPayloads.heartbeat(heartbeat("OK", null, "2026-11-20T14:32:00Z").getBytes(StandardCharsets.UTF_8), NOW, PROPS);
        assertThat(ok.status()).isEqualTo(MarketIndexPayloads.CollectorStatus.OK);
        var failed = MarketIndexPayloads.heartbeat(heartbeat("PROVIDER_FAILED", "TIMEOUT", "2026-11-20T14:32:00Z")
                .getBytes(StandardCharsets.UTF_8), NOW, PROPS);
        assertThat(failed.providerFailureCode()).isEqualTo("TIMEOUT");
        assertThat(rejectHeartbeat(heartbeat("PROVIDER_FAILED", null, "2026-11-20T14:32:00Z")).code).isEqualTo("HEARTBEAT_INVALID");
        assertThat(rejectHeartbeat(heartbeat("OK", "TIMEOUT", "2026-11-20T14:32:00Z")).code).isEqualTo("HEARTBEAT_INVALID");
        assertThat(rejectHeartbeat(heartbeat("PROVIDER_FAILED", "timeout", "2026-11-20T14:32:00Z")).code).isEqualTo("HEARTBEAT_INVALID");
        assertThat(rejectHeartbeat(heartbeat("DOWN", null, "2026-11-20T14:32:00Z")).code).isEqualTo("HEARTBEAT_INVALID");
        // OK must mean the last attempt succeeded; PROVIDER_FAILED must mean it did not.
        String okWithoutSuccess = heartbeat("OK", null, "2026-11-20T14:32:00Z").replace("\"lastSuccessfulCollectionAt\":\"2026-11-20T14:31:00Z\",", "");
        assertThat(rejectHeartbeat(okWithoutSuccess).getMessage()).contains("lastSuccessfulCollectionAt");
        String okWithOldSuccess = heartbeat("OK", null, "2026-11-20T14:32:00Z").replace("\"lastSuccessfulCollectionAt\":\"2026-11-20T14:31:00Z\"", "\"lastSuccessfulCollectionAt\":\"2026-11-20T14:29:00Z\"");
        assertThat(rejectHeartbeat(okWithOldSuccess).code).isEqualTo("HEARTBEAT_INVALID");
        String failedAfterSuccess = heartbeat("PROVIDER_FAILED", "TIMEOUT", "2026-11-20T14:32:00Z").replace("\"lastAttemptAt\"", "\"lastSuccessfulCollectionAt\":\"2026-11-20T14:31:00Z\",\"lastAttemptAt\"");
        assertThat(rejectHeartbeat(failedAfterSuccess).code).isEqualTo("HEARTBEAT_INVALID");
        assertThat(rejectHeartbeat(heartbeat("OK", null, "2026-11-20T14:20:00Z")).code).isEqualTo("HEARTBEAT_CLOCK_SKEW");
        assertThat(rejectHeartbeat(heartbeat("OK", null, "2026-11-20T14:40:00Z")).code).isEqualTo("HEARTBEAT_CLOCK_SKEW");
        assertThat(rejectHeartbeat(heartbeat("OK", null, "2026-11-20T14:28:00Z")).code).isEqualTo("HEARTBEAT_TIME_INVALID");
        assertThat(rejectHeartbeat(heartbeat("OK", null, "2026-11-20T14:32:00Z").replace("\"pendingBatches\":0", "\"pendingBatches\":-1")).code)
                .isEqualTo("HEARTBEAT_INVALID");
    }

    static String withInstruments(String heartbeat, String... entries) {
        return heartbeat.substring(0, heartbeat.length() - 1) + ",\"instruments\":[" + String.join(",", entries) + "]}";
    }

    static String instrument(String symbol, String status, int failures, String code, String lastSuccess) {
        return "{\"symbol\":\"" + symbol + "\",\"status\":\"" + status + "\",\"lastAttemptAt\":\"2026-11-20T14:31:00Z\","
                + "\"consecutiveFailures\":" + failures
                + (lastSuccess == null ? "" : ",\"lastSuccessfulCollectionAt\":\"" + lastSuccess + "\"")
                + (code == null ? "" : ",\"failureCode\":\"" + code + "\"") + "}";
    }

    @Test
    void heartbeatInstrumentHealthIsOptionalAndPerCryptoPair() {
        String base = heartbeat("OK", null, "2026-11-20T14:32:00Z");
        // The four-index heartbeat (every collector before crypto, or with crypto off) is unchanged.
        assertThat(MarketIndexPayloads.heartbeat(base.getBytes(StandardCharsets.UTF_8), NOW, PROPS).instruments()).isEmpty();

        var parsed = MarketIndexPayloads.heartbeat(withInstruments(base,
                instrument("BTCUSD", "FAILED", 3, "UPSTREAM_TIMEOUT", "2026-11-20T14:25:00Z"),
                instrument("ETHUSD", "OK", 0, null, "2026-11-20T14:31:00Z")).getBytes(StandardCharsets.UTF_8), NOW, PROPS);
        // The top-level status still describes the indices: OK while BTC/USD fails.
        assertThat(parsed.status()).isEqualTo(MarketIndexPayloads.CollectorStatus.OK);
        assertThat(parsed.instruments()).extracting(MarketIndexPayloads.InstrumentHealth::symbol)
                .containsExactly(MarketIndexSymbol.BTCUSD, MarketIndexSymbol.ETHUSD);
        var btc = parsed.instruments().get(0);
        assertThat(btc.status()).isEqualTo(MarketIndexPayloads.InstrumentStatus.FAILED);
        assertThat(btc.consecutiveFailures()).isEqualTo(3);
        assertThat(btc.failureCode()).isEqualTo("UPSTREAM_TIMEOUT");
        // Never succeeded: FAILED without a last success.
        assertThat(MarketIndexPayloads.heartbeat(withInstruments(base, instrument("BTCUSD", "FAILED", 1, "X", null))
                .getBytes(StandardCharsets.UTF_8), NOW, PROPS).instruments().get(0).lastSuccessfulCollectionAt()).isNull();

        for (String bad : new String[] {
                instrument("UKX", "OK", 0, null, "2026-11-20T14:31:00Z"),                // indices are the top-level status
                instrument("DOGEUSD", "OK", 0, null, "2026-11-20T14:31:00Z"),
                instrument("BTCUSD", "DOWN", 1, "X", null),
                instrument("BTCUSD", "OK", 1, null, "2026-11-20T14:31:00Z"),            // OK with failures
                instrument("BTCUSD", "OK", 0, "X", "2026-11-20T14:31:00Z"),             // OK with a failure code
                instrument("BTCUSD", "OK", 0, null, null),                              // OK without a success
                instrument("BTCUSD", "OK", 0, null, "2026-11-20T14:30:00Z"),            // OK with an older success
                instrument("BTCUSD", "FAILED", 0, "X", null),                           // FAILED without failures
                instrument("BTCUSD", "FAILED", 2, null, null),                          // FAILED without a code
                instrument("BTCUSD", "FAILED", 2, "X", "2026-11-20T14:31:00Z"),         // FAILED but succeeded last
                instrument("BTCUSD", "FAILED", 2, "lower", null),
                instrument("BTCUSD", "FAILED", -1, "X", null),
                instrument("BTCUSD", "OK", 0, null, "2026-11-20T14:31:00Z").replace("}", ",\"extra\":1}"),
                instrument("BTCUSD", "OK", 0, null, "2026-11-20T14:31:00Z") + "," + instrument("BTCUSD", "OK", 0, null, "2026-11-20T14:31:00Z"),
                "\"BTCUSD\"" }) {
            assertThat(rejectHeartbeat(withInstruments(base, bad)).code).as(bad).isEqualTo("HEARTBEAT_INVALID");
        }
        assertThat(rejectHeartbeat(base.substring(0, base.length() - 1) + ",\"instruments\":{}}").code).isEqualTo("HEARTBEAT_INVALID");
        String future = instrument("ETHUSD", "OK", 0, null, "2026-11-20T14:31:00Z").replace("14:31:00Z", "14:59:00Z");
        assertThat(rejectHeartbeat(withInstruments(base, future)).code).isEqualTo("HEARTBEAT_TIME_INVALID");
    }

    static String crypto(String symbol, String value, String observedAt, String extra) {
        return "{\"symbol\":\"" + symbol + "\",\"value\":\"" + value + "\",\"observedAt\":\"" + observedAt
                + "\",\"observedAtSource\":\"PROVIDER\"" + extra + "}";
    }

    @Test
    void allSixInstrumentsInOneBatchWithTheCryptoReference() {
        var batch = full(batch("provider-x", "2026-11-20T14:32:05Z", String.join(",",
                crypto("ETHUSD", "1640.18", "2026-11-20T14:31:00Z", ""),
                crypto("BTCUSD", "57885.27", "2026-11-20T14:31:00Z", ",\"reference24h\":\"61784.82\",\"reference24hAt\":\"2026-11-19T14:31:00Z\""),
                obs("DJI", "43000.10", "2026-11-20T14:31:00Z", "PROVIDER"), obs("IXIC", "19000.10", "2026-11-20T14:31:00Z", "PROVIDER"),
                obs("SPX", "5900.10", "2026-11-20T14:31:00Z", "PROVIDER"), obs("UKX", "9000.10", "2026-11-20T14:31:00Z", "PROVIDER")))
                .getBytes(StandardCharsets.UTF_8));
        assertThat(batch.observations()).extracting(MarketIndexPayloads.Observation::symbol).containsExactly(MarketIndexSymbol.values());
        var btc = batch.observations().get(4);
        assertThat(btc.reference24h().toPlainString()).isEqualTo("61784.8200");
        assertThat(btc.reference24hAt()).isEqualTo(Instant.parse("2026-11-19T14:31:00Z"));
        assertThat(btc.previousClose()).isNull();
        var eth = batch.observations().get(5);
        assertThat(eth.reference24h()).isNull();
        assertThat(eth.reference24hAt()).isNull();
    }

    @Test
    void cryptoReferenceRules() {
        String at = "2026-11-20T14:31:00Z";
        var cases = java.util.Map.of(
                ",\"previousClose\":\"61000.00\"", "previousClose is not used",
                ",\"reference24h\":\"61000.00\"", "sent together",
                ",\"reference24hAt\":\"2026-11-19T14:31:00Z\"", "sent together",
                ",\"reference24h\":\"61000.00\",\"reference24hAt\":\"2026-11-19T14:32:00Z\"", "exactly 24 hours",
                ",\"reference24h\":\"61000.00\",\"reference24hAt\":\"2026-11-19T14:30:00Z\"", "exactly 24 hours",
                ",\"reference24h\":\"0\",\"reference24hAt\":\"2026-11-19T14:31:00Z\"", "greater than zero",
                ",\"reference24h\":\"61000.00\",\"reference24hAt\":\"2026-11-19\"", "ISO 8601");
        cases.forEach((extra, message) -> {
            var rejected = reject(batch("provider-x", "2026-11-20T14:32:05Z", crypto("BTCUSD", "57885.27", at, extra)));
            assertThat(rejected.code).as(extra).isEqualTo("MARKET_BATCH_INVALID");
            assertThat(rejected.getMessage()).as(extra).contains(message);
        });
        var index = reject(batch("provider-x", "2026-11-20T14:32:05Z", crypto("UKX", "9000.00", at,
                ",\"reference24h\":\"8900.00\",\"reference24hAt\":\"2026-11-19T14:31:00Z\"")));
        assertThat(index.getMessage()).contains("only used for BTCUSD and ETHUSD");
        assertThat(reject(batch("provider-x", "2026-11-20T14:32:05Z", crypto("XRPUSD", "1.00", at, ""))).getMessage())
                .contains("UKX, SPX, IXIC, DJI, BTCUSD, ETHUSD");
    }

    /** A stock-index-only batch hashes exactly as under V17, so replays of earlier stored batches still match. */
    @Test
    void indexOnlyHashIsUnchangedAndTheCryptoReferenceIsHashed() throws Exception {
        var batch = full(batch("provider-x", "2026-11-20T14:32:05Z", obs("SPX", "5987.21", "2026-11-20T14:16:30Z", "EXCHANGE"))
                .getBytes(StandardCharsets.UTF_8));
        String v17 = "market-index-batch-v1\n" + BATCH + "\nprovider-x\n2026-11-20T14:32:05Z\nSPX|5987.2100|-|2026-11-20T14:16:30Z|EXCHANGE\n";
        assertThat(batch.requestSha256()).isEqualTo(java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(v17.getBytes(StandardCharsets.UTF_8))));

        String with = crypto("BTCUSD", "57885.27", "2026-11-20T14:31:00Z", ",\"reference24h\":\"61784.82\",\"reference24hAt\":\"2026-11-19T14:31:00Z\"");
        String other = crypto("BTCUSD", "57885.27", "2026-11-20T14:31:00Z", ",\"reference24h\":\"61784.83\",\"reference24hAt\":\"2026-11-19T14:31:00Z\"");
        String without = crypto("BTCUSD", "57885.27", "2026-11-20T14:31:00Z", "");
        var hashes = java.util.stream.Stream.of(with, other, without)
                .map(o -> full(batch("provider-x", "2026-11-20T14:32:05Z", o).getBytes(StandardCharsets.UTF_8)).requestSha256())
                .distinct().count();
        assertThat(hashes).isEqualTo(3);
    }
}
