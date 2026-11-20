package info.lifeinuk.backend.marketindices;

import info.lifeinuk.backend.support.IsolatedPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** HTTP contract against an isolated PostgreSQL 16: authentication, validation, atomicity, idempotency, provenance. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "life-in-uk.market-indices.collector-keys=k2026a:" + MarketIndexApiTest.KEY_HASH,
        "life-in-uk.market-indices.accepted-sources=mock,provider-x",
        "life-in-uk.market-indices.public-enabled=true",
        "life-in-uk.market-indices.public-sources=provider-x",
        "life-in-uk.market-indices.attribution=Test attribution",
        // A dedicated context: keep its pool small and close it afterwards (shared test server connection limit).
        "spring.datasource.hikari.maximum-pool-size=4"})
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
@org.springframework.test.annotation.DirtiesContext
class MarketIndexApiTest {
    static final String SECRET = "test-only-collector-secret-0123456789abcdefgh";
    static final String KEY_HASH = "ad409a5f1dee7d99135d6bbd06c5f5ec2f2e14dfabd18d3aaa56f3b12674491b"; // SHA-256 of SECRET (checked in clean())
    static final String TOKEN = "k2026a." + SECRET;

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void clean() {
        assertThat(HexFormat.of().formatHex(MarketIndexIngestSecurity.sha256(SECRET))).isEqualTo(KEY_HASH);
        jdbc.update("DELETE FROM market_index_observation");
        jdbc.update("DELETE FROM market_index_ingest_batch");
        jdbc.update("DELETE FROM market_index_collector_heartbeat");
    }

    HttpResponse<String> post(String path, String body, String... headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (headers.length > 0) request.headers(headers);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    HttpResponse<String> ingest(String body) throws Exception {
        return post(MarketIndexController.OBSERVATIONS, body, "Content-Type", "application/json", MarketIndexIngestSecurity.HEADER, TOKEN);
    }

    HttpResponse<String> beat(String body) throws Exception {
        return post(MarketIndexController.HEARTBEAT, body, "Content-Type", "application/json", MarketIndexIngestSecurity.HEADER, TOKEN);
    }

    JsonNode latest() throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + MarketIndexController.LATEST)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        return json.readTree(response.body());
    }

    static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MILLIS); }

    static String batch(UUID id, String source, Instant collectedAt, String... observations) {
        return "{\"schemaVersion\":1,\"batchId\":\"" + id + "\",\"source\":\"" + source + "\",\"collectedAt\":\"" + collectedAt
                + "\",\"observations\":[" + String.join(",", observations) + "]}";
    }

    static String obs(String symbol, String value, Instant observedAt, String timeSource) {
        return "{\"symbol\":\"" + symbol + "\",\"value\":\"" + value + "\",\"previousClose\":\"100.00\",\"observedAt\":\""
                + observedAt + "\",\"observedAtSource\":\"" + timeSource + "\"}";
    }

    static String code(HttpResponse<String> response) {
        return JsonMapper.builder().build().readTree(response.body()).get("code").stringValue();
    }

    int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }

    JsonNode index(JsonNode latest, String symbol) {
        for (JsonNode index : latest.get("indices")) if (index.get("symbol").stringValue().equals(symbol)) return index;
        throw new AssertionError(symbol);
    }

    // ---------------------------------------------------------------- authentication and transport

    @Test
    void credentialIsRequiredAndNeverEchoed() throws Exception {
        String body = batch(UUID.randomUUID(), "provider-x", now(), obs("UKX", "100.5", now().minusSeconds(60), "PROVIDER"));
        for (String[] headers : List.of(new String[] {"Content-Type", "application/json"},
                new String[] {"Content-Type", "application/json", MarketIndexIngestSecurity.HEADER, "k2026a.wrong-secret-wrong-secret-wrong-secret"},
                new String[] {"Content-Type", "application/json", MarketIndexIngestSecurity.HEADER, "k2026b." + SECRET},
                new String[] {"Content-Type", "application/json", "Authorization", "Bearer " + SECRET})) {
            var response = post(MarketIndexController.OBSERVATIONS, body, headers);
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(code(response)).isEqualTo("COLLECTOR_UNAUTHORIZED");
            assertThat(response.body()).doesNotContain(SECRET);
        }
        var heartbeat = post(MarketIndexController.HEARTBEAT, "{}", "Content-Type", "application/json");
        assertThat(heartbeat.statusCode()).isEqualTo(401);
        assertThat(count("market_index_ingest_batch")).isZero();
    }

    @Test
    void contentTypeAndSizeAreEnforced() throws Exception {
        String body = batch(UUID.randomUUID(), "provider-x", now(), obs("UKX", "100.5", now().minusSeconds(60), "PROVIDER"));
        var wrongType = post(MarketIndexController.OBSERVATIONS, body, "Content-Type", "text/plain", MarketIndexIngestSecurity.HEADER, TOKEN);
        assertThat(wrongType.statusCode()).isEqualTo(415);
        var tooLarge = ingest(body.replace("\"schemaVersion\":1,", "\"schemaVersion\":1,\"pad\":\"" + "x".repeat(4200) + "\","));
        assertThat(tooLarge.statusCode()).isEqualTo(413);
        assertThat(code(tooLarge)).isEqualTo("PAYLOAD_TOO_LARGE");
        var malformed = ingest("{\"schemaVersion\":1,");
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(malformed.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(count("market_index_ingest_batch")).isZero();
    }

    // ---------------------------------------------------------------- atomic, idempotent storage

    @Test
    void batchIsStoredOnceAndRetriesAreAcknowledged() throws Exception {
        UUID id = UUID.randomUUID();
        Instant t = now().minusSeconds(120);
        String body = batch(id, "provider-x", now(), obs("UKX", "8123.45", t, "PROVIDER"), obs("SPX", "5987.21", t, "EXCHANGE"));
        var first = ingest(body);
        assertThat(first.statusCode()).isEqualTo(200);
        var ack = json.readTree(first.body());
        assertThat(ack.get("status").stringValue()).isEqualTo("ACCEPTED");
        assertThat(ack.get("batchId").stringValue()).isEqualTo(id.toString());
        assertThat(ack.get("replay").booleanValue()).isFalse();
        assertThat(ack.get("observations").findValuesAsString("result")).containsExactly("STORED", "STORED");
        var retry = json.readTree(ingest(body).body());
        assertThat(retry.get("replay").booleanValue()).isTrue();
        assertThat(retry.get("observations").findValuesAsString("result")).containsOnly("ALREADY_ACCEPTED");
        assertThat(count("market_index_ingest_batch")).isEqualTo(1);
        assertThat(count("market_index_observation")).isEqualTo(2);
    }

    @Test
    void reusedBatchIdWithDifferentContentIsAConflict() throws Exception {
        UUID id = UUID.randomUUID();
        Instant t = now().minusSeconds(120);
        assertThat(ingest(batch(id, "provider-x", now(), obs("UKX", "8123.45", t, "PROVIDER"))).statusCode()).isEqualTo(200);
        var conflict = ingest(batch(id, "provider-x", now(), obs("UKX", "8123.46", t, "PROVIDER")));
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(code(conflict)).isEqualTo("BATCH_ID_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT value::text FROM market_index_observation", String.class)).isEqualTo("8123.4500");
    }

    @Test
    void identicalObservationFromAnotherBatchIsADuplicateAndADifferentValueRollsBackTheWholeBatch() throws Exception {
        Instant t = now().minusSeconds(120);
        assertThat(ingest(batch(UUID.randomUUID(), "provider-x", now(), obs("UKX", "8123.45", t, "PROVIDER"))).statusCode()).isEqualTo(200);
        var duplicate = json.readTree(ingest(batch(UUID.randomUUID(), "provider-x", now(),
                obs("UKX", "8123.4500", t, "PROVIDER"), obs("DJI", "44012.3", t, "EXCHANGE"))).body());
        assertThat(duplicate.get("observations").findValuesAsString("result")).containsExactly("DUPLICATE", "STORED");

        UUID conflicting = UUID.randomUUID();
        var conflict = ingest(batch(conflicting, "provider-x", now(), obs("SPX", "5987.21", t, "EXCHANGE"),
                obs("UKX", "9999.99", t, "PROVIDER")));
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(code(conflict)).isEqualTo("OBSERVATION_CONFLICT");
        // Nothing from the rejected batch remains: not its batch row and not its otherwise valid SPX observation.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM market_index_ingest_batch WHERE batch_id = ?", Integer.class, conflicting)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM market_index_observation WHERE symbol = 'SPX'", Integer.class)).isZero();
        // The same instant from a different source is a different observation.
        assertThat(ingest(batch(UUID.randomUUID(), "mock", now(), obs("UKX", "1.0", t, "COLLECTOR"))).statusCode()).isEqualTo(200);
    }

    @Test
    void concurrentIdenticalRetriesStoreOneBatch() throws Exception {
        String body = batch(UUID.randomUUID(), "provider-x", now(), obs("IXIC", "19210.55", now().minusSeconds(30), "EXCHANGE"));
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(6);
        try {
            var calls = new ArrayList<Future<HttpResponse<String>>>();
            for (int i = 0; i < 6; i++) {
                calls.add(pool.submit((Callable<HttpResponse<String>>) () -> { start.await(); return ingest(body); }));
            }
            start.countDown();
            int replays = 0;
            for (var call : calls) {
                var response = call.get();
                assertThat(response.statusCode()).isEqualTo(200);
                if (json.readTree(response.body()).get("replay").booleanValue()) replays++;
            }
            assertThat(replays).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("market_index_ingest_batch")).isEqualTo(1);
        assertThat(count("market_index_observation")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- ordering and provenance

    @Test
    void lateOlderObservationIsKeptAsHistoryButNeverBecomesLatest() throws Exception {
        Instant newer = now().minusSeconds(60);
        Instant older = now().minusSeconds(3600);
        assertThat(ingest(batch(UUID.randomUUID(), "provider-x", now(), obs("UKX", "8200.00", newer, "PROVIDER"))).statusCode()).isEqualTo(200);
        // Delayed delivery of an older collection (e.g. after an outage), arriving later.
        assertThat(ingest(batch(UUID.randomUUID(), "provider-x", older.plusSeconds(5), obs("UKX", "8100.00", older, "PROVIDER"))).statusCode()).isEqualTo(200);
        assertThat(count("market_index_observation")).isEqualTo(2);
        var ukx = index(latest(), "UKX");
        assertThat(ukx.get("value").stringValue()).isEqualTo("8200.0000");
        assertThat(Instant.parse(ukx.get("provenance").get("observedAt").stringValue())).isEqualTo(newer);
    }

    @Test
    void publicViewExposesProvenanceAndNeverRatesCollectorTimeFresh() throws Exception {
        Instant observed = now().minusSeconds(30);
        Instant collected = now().minusSeconds(20);
        assertThat(ingest(batch(UUID.randomUUID(), "provider-x", collected, obs("DJI", "44012.30", observed, "COLLECTOR"))).statusCode()).isEqualTo(200);
        var latest = latest();
        assertThat(latest.get("attribution").stringValue()).isEqualTo("Test attribution");
        assertThat(latest.get("indices")).hasSize(4);
        var dji = index(latest, "DJI");
        var provenance = dji.get("provenance");
        assertThat(Instant.parse(provenance.get("observedAt").stringValue())).isEqualTo(observed);
        assertThat(provenance.get("observedAtSource").stringValue()).isEqualTo("COLLECTOR");
        assertThat(Instant.parse(provenance.get("collectedAt").stringValue())).isEqualTo(collected);
        assertThat(Instant.parse(provenance.get("receivedAt").stringValue())).isAfter(collected);
        assertThat(dji.get("freshness").get("status").stringValue()).isEqualTo("MARKET_TIME_UNKNOWN");
        assertThat(dji.get("freshness").get("basis").stringValue()).isEqualTo("observedAt");
        assertThat(dji.get("change").stringValue()).isEqualTo("43912.30");
        assertThat(dji.get("market").get("exchange").stringValue()).isEqualTo("NYSE");
        assertThat(index(latest, "UKX").get("freshness").get("status").stringValue()).isEqualTo("UNAVAILABLE");
        assertThat(index(latest, "UKX").get("value").isNull()).isTrue();
    }

    @Test
    void mockObservationsAreStoredPrivatelyAndNeverPublic() throws Exception {
        assertThat(ingest(batch(UUID.randomUUID(), "mock", now(), obs("SPX", "5000.00", now().minusSeconds(10), "COLLECTOR"))).statusCode()).isEqualTo(200);
        var rejected = ingest(batch(UUID.randomUUID(), "mock", now(), obs("SPX", "5000.00", now().minusSeconds(5), "EXCHANGE")));
        assertThat(rejected.statusCode()).isEqualTo(422);
        assertThat(code(rejected)).isEqualTo("MOCK_TIME_SOURCE_INVALID");
        assertThat(count("market_index_observation")).isEqualTo(1);
        var spx = index(latest(), "SPX");
        assertThat(spx.get("value").isNull()).isTrue();
        assertThat(spx.get("freshness").get("status").stringValue()).isEqualTo("UNAVAILABLE");
        // The database itself refuses a mock row that claims a market time.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> jdbc.update("""
                INSERT INTO market_index_observation (symbol, source, observed_at, observed_at_source, value, batch_id)
                SELECT 'UKX', 'mock', now(), 'EXCHANGE', 1, batch_id FROM market_index_ingest_batch LIMIT 1""")))
                .isNotNull();
    }

    @Test
    void semanticRejectionsAre422AndStoreNothing() throws Exception {
        var unaccepted = ingest(batch(UUID.randomUUID(), "provider-y", now(), obs("UKX", "1.0", now().minusSeconds(5), "PROVIDER")));
        assertThat(unaccepted.statusCode()).isEqualTo(422);
        assertThat(code(unaccepted)).isEqualTo("SOURCE_NOT_ACCEPTED");
        var future = ingest(batch(UUID.randomUUID(), "provider-x", now().plusSeconds(200), obs("UKX", "1.0", now().plusSeconds(180), "PROVIDER")));
        assertThat(code(future)).isEqualTo("FUTURE_OBSERVATION");
        var old = ingest(batch(UUID.randomUUID(), "provider-x", now(), obs("UKX", "1.0", now().minus(Duration.ofDays(31)), "PROVIDER")));
        assertThat(code(old)).isEqualTo("OBSERVATION_TOO_OLD");
        assertThat(count("market_index_ingest_batch")).isZero();
    }

    // ---------------------------------------------------------------- heartbeat

    static String heartbeat(String source, String status, String failure, Instant sentAt) {
        return "{\"schemaVersion\":1,\"source\":\"" + source + "\",\"sentAt\":\"" + sentAt + "\",\"collectorStatus\":\"" + status + "\","
                + (failure == null ? "" : "\"providerFailureCode\":\"" + failure + "\",")
                + "\"lastAttemptAt\":\"" + sentAt.minusSeconds(1) + "\",\"lastSuccessfulCollectionAt\":\"" + sentAt.minusSeconds(130)
                + "\",\"pendingBatches\":2,\"oldestPendingCollectedAt\":\"" + sentAt.minusSeconds(250) + "\"}";
    }

    @Test
    void heartbeatDistinguishesHealthyProviderFailedAndDisconnected() throws Exception {
        assertThat(latest().get("collector").get("status").stringValue()).isEqualTo("UNKNOWN");
        var ok = beat(heartbeat("provider-x", "OK", null, now()));
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(json.readTree(ok.body()).get("applied").booleanValue()).isTrue();
        var collector = latest().get("collector");
        assertThat(collector.get("status").stringValue()).isEqualTo("HEALTHY");
        assertThat(collector.get("basis").stringValue()).isEqualTo("heartbeatReceivedAt");
        // A heartbeat never creates or dates an index value.
        assertThat(count("market_index_observation")).isZero();
        assertThat(index(latest(), "UKX").get("freshness").get("status").stringValue()).isEqualTo("UNAVAILABLE");

        assertThat(beat(heartbeat("provider-x", "PROVIDER_FAILED", "UPSTREAM_TIMEOUT", now().plusMillis(5))).statusCode()).isEqualTo(200);
        assertThat(latest().get("collector").get("status").stringValue()).isEqualTo("PROVIDER_FAILED");

        // An out-of-order (older) heartbeat is acknowledged but does not overwrite the newer report.
        var older = beat(heartbeat("provider-x", "OK", null, now().minusSeconds(60)));
        assertThat(json.readTree(older.body()).get("applied").booleanValue()).isFalse();
        assertThat(latest().get("collector").get("status").stringValue()).isEqualTo("PROVIDER_FAILED");

        jdbc.update("UPDATE market_index_collector_heartbeat SET received_at = now() - interval '7 minutes'");
        assertThat(latest().get("collector").get("status").stringValue()).isEqualTo("DISCONNECTED");

        // Mock heartbeats are stored but never describe the public feed.
        jdbc.update("DELETE FROM market_index_collector_heartbeat");
        assertThat(beat(heartbeat("mock", "OK", null, now())).statusCode()).isEqualTo(200);
        assertThat(latest().get("collector").get("status").stringValue()).isEqualTo("UNKNOWN");
    }

    @Test
    void staleHeartbeatIsRejected() throws Exception {
        var stale = beat(heartbeat("provider-x", "OK", null, now().minusSeconds(600)));
        assertThat(stale.statusCode()).isEqualTo(422);
        assertThat(code(stale)).isEqualTo("HEARTBEAT_CLOCK_SKEW");
    }

    // ---------------------------------------------------------------- retention

    @Autowired MarketIndexStore store;

    @Test
    void retentionNeverRemovesTheLatestObservationOfASymbolAndSource() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO market_index_ingest_batch (batch_id, source, collected_at, request_sha256, observation_count, credential_id, received_at)
                VALUES (?, 'provider-x', now() - interval '800 days', repeat('a', 64), 3, 'k2026a', now() - interval '800 days')""", id);
        for (String[] row : List.of(new String[] {"UKX", "802"}, new String[] {"UKX", "801"}, new String[] {"SPX", "801"})) {
            jdbc.update("INSERT INTO market_index_observation (symbol, source, observed_at, observed_at_source, value, batch_id) "
                    + "VALUES (?, 'provider-x', now() - make_interval(days => ?), 'PROVIDER', 1, ?)", row[0], Integer.parseInt(row[1]), id);
        }
        assertThat(store.purgeBefore(Instant.now().minus(Duration.ofDays(730)), 5000)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT symbol FROM market_index_observation ORDER BY symbol", String.class)).containsExactly("SPX", "UKX");
        assertThat(count("market_index_ingest_batch")).isEqualTo(1); // still referenced
    }
}
