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
        assertThat(reject(batch("provider-x", "2026-11-20T14:32:05Z", "")).getMessage()).contains("1 to 4");
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
}
