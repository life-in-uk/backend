package info.lifeinuk.backend.marketindices;

import info.lifeinuk.backend.support.IsolatedPostgres;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** HTTP contract against an isolated PostgreSQL 16: authentication, source binding, atomicity, idempotency, provenance. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // k2026a may write the two licensed test sources and mock; kmock may write mock only.
        "life-in-uk.market-indices.collector-keys=k2026a:" + MarketIndexApiTest.KEY_HASH + ":provider-x|provider-y|mock,"
                + "kmock:" + MarketIndexApiTest.MOCK_KEY_HASH + ":mock",
        "life-in-uk.market-indices.accepted-sources=mock,provider-x,provider-y",
        "life-in-uk.market-indices.public-enabled=true",
        "life-in-uk.market-indices.public-sources=provider-x,provider-y",
        "life-in-uk.market-indices.attribution=Test attribution",
        // A dedicated context: keep its pool small and close it afterwards (shared test server connection limit).
        "spring.datasource.hikari.maximum-pool-size=4"})
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
@DirtiesContext
class MarketIndexApiTest {
    static final String SECRET = "test-only-collector-secret-0123456789abcdefgh";
    static final String KEY_HASH = "ad409a5f1dee7d99135d6bbd06c5f5ec2f2e14dfabd18d3aaa56f3b12674491b";
    static final String MOCK_SECRET = "test-only-mock-collector-secret-9876543210zyxw";
    static final String MOCK_KEY_HASH = "81b9893988047813351cc8da584649bf673baec9ab899cd2b3ab67fc45949087";
    static final String TOKEN = "k2026a." + SECRET;
    static final String MOCK_TOKEN = "kmock." + MOCK_SECRET;

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired MarketIndexStore store;
    @Autowired MarketIndexProperties properties;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void clean() {
        assertThat(HexFormat.of().formatHex(MarketIndexIngestSecurity.sha256(SECRET))).isEqualTo(KEY_HASH);
        assertThat(HexFormat.of().formatHex(MarketIndexIngestSecurity.sha256(MOCK_SECRET))).isEqualTo(MOCK_KEY_HASH);
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

    HttpResponse<String> ingest(String body) throws Exception { return ingest(body, TOKEN); }

    HttpResponse<String> ingest(String body, String token) throws Exception {
        return post(MarketIndexController.OBSERVATIONS, body, "Content-Type", "application/json", MarketIndexIngestSecurity.HEADER, token);
    }

    HttpResponse<String> beat(String body) throws Exception { return beat(body, TOKEN); }

    HttpResponse<String> beat(String body, String token) throws Exception {
        return post(MarketIndexController.HEARTBEAT, body, "Content-Type", "application/json", MarketIndexIngestSecurity.HEADER, token);
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

    String sourceStatus(JsonNode latest, String source) {
        for (JsonNode s : latest.get("collector").get("sources")) if (s.get("source").stringValue().equals(source)) return s.get("status").stringValue();
        throw new AssertionError(source);
    }

    // ---------------------------------------------------------------- authentication and transport

    @Test
    void credentialIsRequiredAndNeverEchoed() throws Exception {
        String body = batch(UUID.randomUUID(), "provider-x", now(), obs("UKX", "100.5", now().minusSeconds(60), "PROVIDER"));
        for (String[] headers : List.of(new String[] {"Content-Type", "application/json"},
                new String[] {"Content-Type", "application/json", MarketIndexIngestSecurity.HEADER, "k2026a.wrong-secret-wrong-secret-wrong-secret"},
                new String[] {"Content-Type", "application/json", MarketIndexIngestSecurity.HEADER, "kmock." + SECRET},
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
    void mockOnlyCredentialCannotImpersonateALicensedSource() throws Exception {
        Instant t = now().minusSeconds(60);
        var observations = ingest(batch(UUID.randomUUID(), "provider-x", now(), obs("UKX", "8123.45", t, "PROVIDER")), MOCK_TOKEN);
        assertThat(observations.statusCode()).isEqualTo(403);
        assertThat(code(observations)).isEqualTo("SOURCE_NOT_AUTHORIZED");
        var heartbeat = beat(heartbeat("provider-x", "OK", null, now()), MOCK_TOKEN);
        assertThat(heartbeat.statusCode()).isEqualTo(403);
        assertThat(code(heartbeat)).isEqualTo("SOURCE_NOT_AUTHORIZED");
        assertThat(count("market_index_ingest_batch")).isZero();
        assertThat(count("market_index_collector_heartbeat")).isZero();
        // The mock key can write its own source, which stays private.
        assertThat(ingest(batch(UUID.randomUUID(), "mock", now(), obs("UKX", "1.0", t, "COLLECTOR")), MOCK_TOKEN).statusCode()).isEqualTo(200);
        assertThat(beat(heartbeat("mock", "OK", null, now()), MOCK_TOKEN).statusCode()).isEqualTo(200);
        assertThat(index(latest(), "UKX").get("freshness").get("status").stringValue()).isEqualTo("UNAVAILABLE");
        assertThat(latest().get("collector").get("status").stringValue()).isEqualTo("UNKNOWN");
    }

    @Test
    void replayCannotBypassSourceAuthorization() throws Exception {
        String body = batch(UUID.randomUUID(), "provider-x", now(), obs("SPX", "5987.21", now().minusSeconds(30), "EXCHANGE"));
        assertThat(ingest(body).statusCode()).isEqualTo(200);
        var replayByMockKey = ingest(body, MOCK_TOKEN);
        assertThat(replayByMockKey.statusCode()).isEqualTo(403);
        assertThat(code(replayByMockKey)).isEqualTo("SOURCE_NOT_AUTHORIZED");
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
        assertThat(jdbc.queryForObject("SELECT credential_id FROM market_index_ingest_batch", String.class)).isEqualTo("k2026a");
    }

    @Test
    void committedBatchRetriedAfterCrossingTheBackfillCutoffIsStillAReplay() throws Exception {
        UUID id = UUID.randomUUID();
        // Accepted when the observation was just inside the window...
        Instant observed = now().minus(properties.maxBackfill()).plusSeconds(3);
        String body = batch(id, "provider-x", observed.plusSeconds(1), obs("UKX", "8123.45", observed, "PROVIDER"));
        assertThat(ingest(body).statusCode()).isEqualTo(200);
        Thread.sleep(3500);
        // ...retried after it crossed the cutoff (lost acknowledgement): replay, not OBSERVATION_TOO_OLD.
        var retry = ingest(body);
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(json.readTree(retry.body()).get("replay").booleanValue()).isTrue();
        // A new batch with the same content is outside the window now.
        var fresh = ingest(batch(UUID.randomUUID(), "provider-x", observed.plusSeconds(1), obs("UKX", "8123.45", observed, "PROVIDER")));
        assertThat(fresh.statusCode()).isEqualTo(422);
        assertThat(code(fresh)).isEqualTo("OBSERVATION_TOO_OLD");
        // And a changed body under the same id is still a conflict, not a replay.
        var changed = ingest(batch(id, "provider-x", observed.plusSeconds(1), obs("UKX", "8123.46", observed, "PROVIDER")));
        assertThat(changed.statusCode()).isEqualTo(409);
        assertThat(code(changed)).isEqualTo("BATCH_ID_CONFLICT");
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
    void identicalObservationFromAnotherBatchIsADuplicateAndADifferentValueIsACorrectionThatNeverBlocksTheBatch() throws Exception {
        Instant t = now().minusSeconds(120);
        assertThat(ingest(batch(UUID.randomUUID(), "provider-x", now(), obs("UKX", "8123.45", t, "PROVIDER"))).statusCode()).isEqualTo(200);
        var duplicate = json.readTree(ingest(batch(UUID.randomUUID(), "provider-x", now(),
                obs("UKX", "8123.4500", t, "PROVIDER"), obs("DJI", "44012.3", t, "EXCHANGE"))).body());
        assertThat(duplicate.get("observations").findValuesAsString("result")).containsExactly("DUPLICATE", "STORED");

        UUID correcting = UUID.randomUUID();
        var corrected = ingest(batch(correcting, "provider-x", now(), obs("SPX", "5987.21", t, "EXCHANGE"),
                obs("UKX", "9999.99", t, "PROVIDER")));
        assertThat(corrected.statusCode()).isEqualTo(200);
        assertThat(json.readTree(corrected.body()).get("observations").findValuesAsString("result")).containsExactly("CORRECTED", "STORED");
        assertThat(jdbc.queryForObject("SELECT value FROM market_index_observation WHERE symbol = 'UKX'", BigDecimal.class))
                .isEqualByComparingTo("9999.99");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM market_index_observation WHERE symbol = 'SPX'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT previous_value FROM market_index_observation_revision", BigDecimal.class))
                .isEqualByComparingTo("8123.45");
        assertThat(ingest(batch(UUID.randomUUID(), "mock", now(), obs("UKX", "1.0", t, "COLLECTOR"))).statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- upstream corrections

    String result(HttpResponse<String> response, String symbol) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        for (JsonNode o : json.readTree(response.body()).get("observations")) {
            if (o.get("symbol").stringValue().equals(symbol)) return o.get("result").stringValue();
        }
        throw new AssertionError(symbol);
    }

    BigDecimal stored(String symbol, Instant observedAt) {
        return jdbc.queryForObject("SELECT value FROM market_index_observation WHERE symbol = ? AND source = 'provider-x' AND observed_at = ?",
                BigDecimal.class, symbol, java.sql.Timestamp.from(observedAt));
    }

    int revisions(String symbol) {
        return jdbc.queryForObject("SELECT count(*) FROM market_index_observation_revision WHERE symbol = ?", Integer.class, symbol);
    }

    /**
     * The production incident of 2026-11-25/26: Sina revised the Dow's 21:00 close from 50185.6914 to 50182.9492 after
     * it was stored. Every later batch repeated the revised close, was rejected whole with 409 OBSERVATION_CONFLICT and
     * took the fresh FTSE 100 values with it. Now the revision corrects the close and the FTSE 100 is stored.
     */
    @Test
    void theRealDowCloseCorrectionNoLongerBlocksTheFtse() throws Exception {
        Instant close = now().truncatedTo(ChronoUnit.MINUTES).minus(Duration.ofHours(19));
        Instant first = close.plusSeconds(6 * 60 + 11);
        var original = ingest(batch(UUID.randomUUID(), "provider-x", first, obs("UKX", "10433.6602", close.minus(Duration.ofMinutes(270)), "PROVIDER"),
                obs("SPX", "7182.7798", close, "PROVIDER"), obs("IXIC", "23585.8008", close, "PROVIDER"), obs("DJI", "50185.6914", close, "PROVIDER")));
        assertThat(result(original, "DJI")).isEqualTo("STORED");

        // Next cycle: the revised close beside unchanged ones.
        var revised = ingest(batch(UUID.randomUUID(), "provider-x", first.plusSeconds(300),
                obs("UKX", "10433.6602", close.minus(Duration.ofMinutes(270)), "PROVIDER"), obs("SPX", "7182.7798", close, "PROVIDER"),
                obs("IXIC", "23585.8008", close, "PROVIDER"), obs("DJI", "50182.9492", close, "PROVIDER")));
        assertThat(result(revised, "DJI")).isEqualTo("CORRECTED");
        assertThat(result(revised, "SPX")).isEqualTo("DUPLICATE");

        // The next trading day: a fresh FTSE 100 minute with the US closes repeated (US holiday) is stored.
        Instant ftse = now().truncatedTo(ChronoUnit.MINUTES).minusSeconds(60);
        var nextDay = ingest(batch(UUID.randomUUID(), "provider-x", now(), obs("UKX", "10630.65", ftse, "PROVIDER"),
                obs("SPX", "7182.7798", close, "PROVIDER"), obs("IXIC", "23585.8008", close, "PROVIDER"), obs("DJI", "50182.9492", close, "PROVIDER")));
        assertThat(result(nextDay, "UKX")).isEqualTo("STORED");
        assertThat(result(nextDay, "DJI")).isEqualTo("DUPLICATE");

        var latest = latest();
        assertThat(index(latest, "DJI").get("value").stringValue()).isEqualTo("50182.9492");
        assertThat(index(latest, "UKX").get("value").stringValue()).isEqualTo("10630.6500");
        assertThat(Instant.parse(index(latest, "UKX").get("provenance").get("observedAt").stringValue())).isEqualTo(ftse);
        // The replaced close is kept for audit with both batches.
        var audit = jdbc.queryForMap("SELECT previous_value, previous_batch_id, batch_id, previous_collected_at, collected_at "
                + "FROM market_index_observation_revision WHERE symbol = 'DJI'");
        assertThat((BigDecimal) audit.get("previous_value")).isEqualByComparingTo("50185.6914");
        assertThat(audit.get("previous_batch_id").toString()).isEqualTo(json.readTree(original.body()).get("batchId").stringValue());
        assertThat(audit.get("batch_id").toString()).isEqualTo(json.readTree(revised.body()).get("batchId").stringValue());
        assertThat(revisions("DJI")).isOne();
    }

    @Test
    void anOlderOrReplayedBatchNeverOverwritesANewerCorrection() throws Exception {
        Instant t = now().minusSeconds(600);
        Instant c0 = now().minusSeconds(300);
        UUID oldBatch = UUID.randomUUID();
        String oldBody = batch(oldBatch, "provider-x", c0, obs("SPX", "6910.79", t, "PROVIDER"));
        assertThat(result(ingest(oldBody), "SPX")).isEqualTo("STORED");
        assertThat(result(ingest(batch(UUID.randomUUID(), "provider-x", c0.plusSeconds(120), obs("SPX", "6910.39", t, "PROVIDER"))), "SPX"))
                .isEqualTo("CORRECTED");

        // The original batch again (an outbox retry after a lost acknowledgement): a replay, nothing changes.
        var replay = ingest(oldBody);
        assertThat(json.readTree(replay.body()).get("replay").booleanValue()).isTrue();
        assertThat(stored("SPX", t)).isEqualByComparingTo("6910.39");
        // The same batch id with different content is still refused.
        assertThat(code(ingest(batch(oldBatch, "provider-x", c0, obs("SPX", "6910.54", t, "PROVIDER"))))).isEqualTo("BATCH_ID_CONFLICT");

        // A different batch collected before the correction (out of order, e.g. one rejected earlier and re-sent):
        // superseded, with nothing changed and no revision.
        var older = ingest(batch(UUID.randomUUID(), "provider-x", c0.plusSeconds(60), obs("SPX", "6910.54", t, "PROVIDER"),
                obs("IXIC", "22000.10", t, "PROVIDER")));
        assertThat(result(older, "SPX")).isEqualTo("SUPERSEDED");
        assertThat(result(older, "IXIC")).isEqualTo("STORED");
        assertThat(stored("SPX", t)).isEqualByComparingTo("6910.39");
        assertThat(revisions("SPX")).isOne();
        assertThat(index(latest(), "SPX").get("value").stringValue()).isEqualTo("6910.3900");
    }

    @Test
    void correctionPrecedenceDependsOnTheBatchesNotOnArrivalOrder() throws Exception {
        // Same collectedAt, different values: the greater batch id wins whichever arrives first.
        Instant collected = now().minusSeconds(60);
        UUID low = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID high = UUID.fromString("ffffffff-0000-4000-8000-000000000001");
        Instant a = now().minusSeconds(600);
        Instant b = now().minusSeconds(540);
        ingest(batch(low, "provider-x", collected, obs("DJI", "100.00", a, "PROVIDER")));
        ingest(batch(high, "provider-x", collected, obs("DJI", "200.00", a, "PROVIDER")));
        ingest(batch(UUID.fromString("ffffffff-0000-4000-8000-000000000002"), "provider-x", collected, obs("DJI", "200.00", b, "PROVIDER")));
        ingest(batch(UUID.fromString("00000000-0000-4000-8000-000000000002"), "provider-x", collected, obs("DJI", "100.00", b, "PROVIDER")));
        assertThat(stored("DJI", a)).isEqualByComparingTo("200.00");
        assertThat(stored("DJI", b)).isEqualByComparingTo("200.00");

        // A value that flips and flips back (Sina's settling close): the latest collection wins each time, audited.
        Instant close = now().minusSeconds(900);
        Instant c = now().minusSeconds(400);
        for (String v : new String[] {"7237.36", "7237.79", "7237.17", "7237.79", "7237.17", "7237.17"}) {
            c = c.plusSeconds(30);
            ingest(batch(UUID.randomUUID(), "provider-x", c, obs("SPX", v, close, "PROVIDER")));
        }
        assertThat(stored("SPX", close)).isEqualByComparingTo("7237.17");
        assertThat(revisions("SPX")).isEqualTo(4);
    }

    @Test
    void concurrentCorrectionsConvergeOnTheNewestCollectionWithoutDeadlocks() throws Exception {
        Instant t = now().minusSeconds(600);
        Instant base = now().minusSeconds(300);
        ingest(batch(UUID.randomUUID(), "provider-x", base, obs("UKX", "100.00", t, "PROVIDER"), obs("DJI", "100.00", t, "PROVIDER")));
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(8);
        try {
            var calls = new ArrayList<Future<HttpResponse<String>>>();
            for (int i = 1; i <= 8; i++) {
                String value = (100 + i) + ".00";
                // Alternate the order of the two symbols in the request; locking order is fixed server-side.
                String body = i % 2 == 0
                        ? batch(UUID.randomUUID(), "provider-x", base.plusSeconds(i), obs("UKX", value, t, "PROVIDER"), obs("DJI", value, t, "PROVIDER"))
                        : batch(UUID.randomUUID(), "provider-x", base.plusSeconds(i), obs("DJI", value, t, "PROVIDER"), obs("UKX", value, t, "PROVIDER"));
                calls.add(pool.submit((Callable<HttpResponse<String>>) () -> { start.await(); return ingest(body); }));
            }
            start.countDown();
            for (var call : calls) assertThat(call.get().statusCode()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        // Whatever the commit order, the newest collection (i = 8) holds, and every applied change is audited.
        assertThat(stored("UKX", t)).isEqualByComparingTo("108.00");
        assertThat(stored("DJI", t)).isEqualByComparingTo("108.00");
        assertThat(revisions("UKX")).isBetween(1, 8);
        assertThat(revisions("UKX")).isEqualTo(revisions("DJI"));
        assertThat(count("market_index_ingest_batch")).isEqualTo(9);
    }

    @Test
    void cryptoAndIndexCorrectionsAreIndependentAndRetentionStillWorks() throws Exception {
        Instant at = now().truncatedTo(ChronoUnit.MINUTES).minusSeconds(120);
        Instant c0 = now().minusSeconds(60);
        var first = ingest(batch(UUID.randomUUID(), "provider-x", c0, obs("UKX", "101.00", at, "PROVIDER"),
                crypto("BTCUSD", "57885.27", at, null), crypto("ETHUSD", "1640.18", at, null)));
        assertThat(result(first, "BTCUSD")).isEqualTo("STORED");
        // A revised BTC/USD bar beside an unchanged ETH/USD and a new index minute: only BTC changes.
        Instant next = at.plusSeconds(60);
        var second = ingest(batch(UUID.randomUUID(), "provider-x", c0.plusSeconds(30), obs("UKX", "102.00", next, "PROVIDER"),
                crypto("BTCUSD", "57890.00", at, null), crypto("ETHUSD", "1640.18", at, null)));
        assertThat(result(second, "BTCUSD")).isEqualTo("CORRECTED");
        assertThat(result(second, "ETHUSD")).isEqualTo("DUPLICATE");
        assertThat(result(second, "UKX")).isEqualTo("STORED");
        assertThat(revisions("BTCUSD")).isOne();
        assertThat(revisions("ETHUSD")).isZero();

        // The superseded batch is no longer referenced by any observation: batch retention can delete it (revisions
        // keep its id as a value, not a reference), and deleting an observation removes its revisions.
        UUID firstBatch = UUID.fromString(json.readTree(first.body()).get("batchId").stringValue());
        jdbc.update("UPDATE market_index_observation SET batch_id = ? WHERE batch_id = ?",
                UUID.fromString(json.readTree(second.body()).get("batchId").stringValue()), firstBatch);
        assertThat(store.deleteOrphanBatchesBefore(Instant.now().plusSeconds(60), 100)).isEqualTo(1);
        jdbc.update("DELETE FROM market_index_observation WHERE symbol = 'BTCUSD'");
        assertThat(revisions("BTCUSD")).isZero();
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

    // ---------------------------------------------------------------- ordering, future times and provenance

    @Test
    void lateOlderObservationIsKeptAsHistoryButNeverBecomesLatest() throws Exception {
        Instant newer = now().minusSeconds(60);
        Instant older = now().minusSeconds(3600);
        assertThat(ingest(batch(UUID.randomUUID(), "provider-x", now(), obs("UKX", "8200.00", newer, "PROVIDER"))).statusCode()).isEqualTo(200);
        assertThat(ingest(batch(UUID.randomUUID(), "provider-x", older.plusSeconds(5), obs("UKX", "8100.00", older, "PROVIDER"))).statusCode()).isEqualTo(200);
        assertThat(count("market_index_observation")).isEqualTo(2);
        var ukx = index(latest(), "UKX");
        assertThat(ukx.get("value").stringValue()).isEqualTo("8200.0000");
        assertThat(Instant.parse(ukx.get("provenance").get("observedAt").stringValue())).isEqualTo(newer);
    }

    @Test
    void futureDatedObservationDoesNotDisplaceTheCurrentQuoteOrRateFresh() throws Exception {
        Instant valid = now().minusSeconds(30);
        Instant future = now().plusSeconds(25); // inside the 30 s future-skew allowance, so it is accepted and stored
        assertThat(ingest(batch(UUID.randomUUID(), "provider-x", now(), obs("SPX", "5900.00", valid, "EXCHANGE"))).statusCode()).isEqualTo(200);
        assertThat(ingest(batch(UUID.randomUUID(), "provider-x", future, obs("SPX", "9999.00", future, "EXCHANGE"))).statusCode()).isEqualTo(200);
        assertThat(count("market_index_observation")).isEqualTo(2);
        var spx = index(latest(), "SPX");
        assertThat(spx.get("value").stringValue()).isEqualTo("5900.0000");
        assertThat(Instant.parse(spx.get("provenance").get("observedAt").stringValue())).isEqualTo(valid);
        // Beyond the allowance it is rejected outright.
        var tooFar = ingest(batch(UUID.randomUUID(), "provider-x", now().plusSeconds(60), obs("SPX", "1.00", now().plusSeconds(60), "EXCHANGE")));
        assertThat(code(tooFar)).isEqualTo("FUTURE_OBSERVATION");
    }

    @Test
    void newestAcrossPublicSourcesWins() throws Exception {
        Instant older = now().minusSeconds(300);
        Instant newer = now().minusSeconds(60);
        assertThat(ingest(batch(UUID.randomUUID(), "provider-x", now(), obs("DJI", "44000.00", older, "EXCHANGE"))).statusCode()).isEqualTo(200);
        assertThat(ingest(batch(UUID.randomUUID(), "provider-y", now(), obs("DJI", "44100.00", newer, "EXCHANGE"))).statusCode()).isEqualTo(200);
        var dji = index(latest(), "DJI");
        assertThat(dji.get("source").stringValue()).isEqualTo("provider-y");
        assertThat(dji.get("value").stringValue()).isEqualTo("44100.0000");
    }

    @Test
    void publicViewExposesProvenanceAndNeverRatesCollectorTimeFresh() throws Exception {
        Instant observed = now().minusSeconds(30);
        Instant collected = now().minusSeconds(20);
        assertThat(ingest(batch(UUID.randomUUID(), "provider-x", collected, obs("DJI", "44012.30", observed, "COLLECTOR"))).statusCode()).isEqualTo(200);
        var latest = latest();
        assertThat(latest.get("attribution").stringValue()).isEqualTo("Test attribution");
        assertThat(latest.get("indices")).hasSize(6);
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
        assertThat(dji.get("collectorStatus").stringValue()).isEqualTo("UNKNOWN");
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
        assertThat(catchThrowable(() -> jdbc.update("""
                INSERT INTO market_index_observation (symbol, source, observed_at, observed_at_source, value, batch_id)
                SELECT 'UKX', 'mock', now(), 'EXCHANGE', 1, batch_id FROM market_index_ingest_batch LIMIT 1"""))).isNotNull();
    }

    @Test
    void semanticRejectionsStoreNothing() throws Exception {
        var unaccepted = ingest(batch(UUID.randomUUID(), "provider-z", now(), obs("UKX", "1.0", now().minusSeconds(5), "PROVIDER")));
        assertThat(unaccepted.statusCode()).isEqualTo(422);
        assertThat(code(unaccepted)).isEqualTo("SOURCE_NOT_ACCEPTED");
        var future = ingest(batch(UUID.randomUUID(), "provider-x", now().plusSeconds(200), obs("UKX", "1.0", now().plusSeconds(180), "PROVIDER")));
        assertThat(code(future)).isEqualTo("FUTURE_OBSERVATION");
        var old = ingest(batch(UUID.randomUUID(), "provider-x", now(), obs("UKX", "1.0", now().minus(Duration.ofDays(31)), "PROVIDER")));
        assertThat(code(old)).isEqualTo("OBSERVATION_TOO_OLD");
        assertThat(count("market_index_ingest_batch")).isZero();
    }

    // ---------------------------------------------------------------- heartbeat

    /** OK: the last attempt succeeded (success == attempt). PROVIDER_FAILED: success two cycles earlier, or never. */
    static String heartbeat(String source, String status, String failure, Instant sentAt) {
        return heartbeat(source, status, failure, sentAt, sentAt.minusSeconds(1), "OK".equals(status) ? sentAt.minusSeconds(1)
                : "NEVER".equals(failure) ? null : sentAt.minusSeconds(241));
    }

    static String heartbeat(String source, String status, String failure, Instant sentAt, Instant lastAttempt, Instant lastSuccess) {
        return "{\"schemaVersion\":1,\"source\":\"" + source + "\",\"sentAt\":\"" + sentAt + "\",\"collectorStatus\":\"" + status + "\","
                + (failure == null ? "" : "\"providerFailureCode\":\"" + failure + "\",")
                + "\"lastAttemptAt\":\"" + lastAttempt + "\","
                + (lastSuccess == null ? "" : "\"lastSuccessfulCollectionAt\":\"" + lastSuccess + "\",")
                + "\"pendingBatches\":2,\"oldestPendingCollectedAt\":\"" + sentAt.minusSeconds(250) + "\"}";
    }

    @Test
    void heartbeatDistinguishesHealthyProviderFailedNeverSucceededAndDisconnected() throws Exception {
        assertThat(latest().get("collector").get("status").stringValue()).isEqualTo("UNKNOWN");

        // Running, but no collection has ever succeeded: never HEALTHY.
        assertThat(beat(heartbeat("provider-x", "PROVIDER_FAILED", "NEVER", now())).statusCode()).isEqualTo(200);
        assertThat(sourceStatus(latest(), "provider-x")).isEqualTo("NO_SUCCESSFUL_COLLECTION");

        var ok = beat(heartbeat("provider-x", "OK", null, now().plusMillis(5)));
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(json.readTree(ok.body()).get("applied").booleanValue()).isTrue();
        var collector = latest().get("collector");
        assertThat(sourceStatus(latest(), "provider-x")).isEqualTo("HEALTHY");
        assertThat(collector.get("basis").stringValue()).isEqualTo("heartbeatReceivedAt");
        assertThat(count("market_index_observation")).isZero();

        // Recent heartbeat, last collection failed: PROVIDER_FAILED, not HEALTHY.
        assertThat(beat(heartbeat("provider-x", "PROVIDER_FAILED", "UPSTREAM_TIMEOUT", now().plusMillis(10))).statusCode()).isEqualTo(200);
        assertThat(sourceStatus(latest(), "provider-x")).isEqualTo("PROVIDER_FAILED");

        // An older report is acknowledged but does not overwrite the newer status.
        var older = beat(heartbeat("provider-x", "OK", null, now().minusSeconds(60)));
        assertThat(json.readTree(older.body()).get("applied").booleanValue()).isFalse();
        assertThat(sourceStatus(latest(), "provider-x")).isEqualTo("PROVIDER_FAILED");

        jdbc.update("UPDATE market_index_collector_heartbeat SET received_at = now() - interval '7 minutes'");
        assertThat(sourceStatus(latest(), "provider-x")).isEqualTo("DISCONNECTED");
    }

    @Test
    void oneHealthySourceCannotMaskAnotherFailedOrDisconnectedSource() throws Exception {
        assertThat(beat(heartbeat("provider-x", "OK", null, now())).statusCode()).isEqualTo(200);
        // provider-y has never reported: the aggregate is not HEALTHY.
        var latest = latest();
        assertThat(sourceStatus(latest, "provider-x")).isEqualTo("HEALTHY");
        assertThat(sourceStatus(latest, "provider-y")).isEqualTo("UNKNOWN");
        assertThat(latest.get("collector").get("status").stringValue()).isEqualTo("UNKNOWN");

        assertThat(beat(heartbeat("provider-y", "PROVIDER_FAILED", "UPSTREAM_TIMEOUT", now())).statusCode()).isEqualTo(200);
        assertThat(latest().get("collector").get("status").stringValue()).isEqualTo("PROVIDER_FAILED");

        // provider-y goes silent while provider-x keeps reporting HEALTHY: the aggregate is DISCONNECTED.
        jdbc.update("UPDATE market_index_collector_heartbeat SET received_at = now() - interval '10 minutes' WHERE source = 'provider-y'");
        assertThat(beat(heartbeat("provider-x", "OK", null, now().plusMillis(5))).statusCode()).isEqualTo(200);
        latest = latest();
        assertThat(sourceStatus(latest, "provider-x")).isEqualTo("HEALTHY");
        assertThat(sourceStatus(latest, "provider-y")).isEqualTo("DISCONNECTED");
        assertThat(latest.get("collector").get("status").stringValue()).isEqualTo("DISCONNECTED");

        // Each index reports the collector state of the source it came from.
        assertThat(ingest(batch(UUID.randomUUID(), "provider-y", now(), obs("SPX", "5000.00", now().minusSeconds(20), "EXCHANGE"))).statusCode()).isEqualTo(200);
        assertThat(index(latest(), "SPX").get("collectorStatus").stringValue()).isEqualTo("DISCONNECTED");
    }

    @Test
    void collectorClockSkewNeitherFakesHealthNorDisconnectsALiveCollector() throws Exception {
        // Collector clock 4 minutes fast (inside the 5-minute allowance): health uses collector times only relative to
        // each other, so the report is still HEALTHY.
        Instant fast = now().plusSeconds(240);
        assertThat(beat(heartbeat("provider-x", "OK", null, fast)).statusCode()).isEqualTo(200);
        assertThat(sourceStatus(latest(), "provider-x")).isEqualTo("HEALTHY");

        // The clock then steps back 4 minutes (NTP correction): the status is not overwritten by the "older" report,
        // but liveness is refreshed, so a live collector is never shown DISCONNECTED.
        // Simulate the last applied report being received 10 minutes ago (past the 6-minute timeout).
        jdbc.update("UPDATE market_index_collector_heartbeat SET received_at = now() - interval '10 minutes'");
        assertThat(sourceStatus(latest(), "provider-x")).isEqualTo("DISCONNECTED");
        var stepped = beat(heartbeat("provider-x", "PROVIDER_FAILED", "UPSTREAM_TIMEOUT", now()));
        assertThat(stepped.statusCode()).isEqualTo(200);
        assertThat(json.readTree(stepped.body()).get("applied").booleanValue()).isFalse();
        // The stepped-back report refreshed liveness without overwriting the newer status.
        assertThat(sourceStatus(latest(), "provider-x")).isEqualTo("HEALTHY");

        // A collector that heartbeats but has not attempted a collection for longer than the timeout is not HEALTHY.
        jdbc.update("DELETE FROM market_index_collector_heartbeat");
        Instant sent = now();
        assertThat(beat(heartbeat("provider-x", "OK", null, sent, sent.minusSeconds(600), sent.minusSeconds(600))).statusCode()).isEqualTo(200);
        assertThat(sourceStatus(latest(), "provider-x")).isEqualTo("COLLECTION_STALLED");

        // Outside the allowance the heartbeat is rejected outright.
        var skewed = beat(heartbeat("provider-x", "OK", null, now().plusSeconds(600)));
        assertThat(skewed.statusCode()).isEqualTo(422);
        assertThat(code(skewed)).isEqualTo("HEARTBEAT_CLOCK_SKEW");
    }

    @Test
    void okHeartbeatMustCarryASuccessAtTheLastAttempt() throws Exception {
        Instant sent = now();
        var neverSucceeded = beat(heartbeat("provider-x", "OK", null, sent, sent.minusSeconds(1), null));
        assertThat(neverSucceeded.statusCode()).isEqualTo(400);
        var oldSuccess = beat(heartbeat("provider-x", "OK", null, sent, sent.minusSeconds(1), sent.minusSeconds(130)));
        assertThat(oldSuccess.statusCode()).isEqualTo(400);
        assertThat(count("market_index_collector_heartbeat")).isZero();
    }

    // ---------------------------------------------------------------- retention (never on the request path)

    @Test
    void retentionRunsWithoutUploadsIsBoundedAndKeepsTheNewestRow() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO market_index_ingest_batch (batch_id, source, collected_at, request_sha256, observation_count, credential_id, received_at)
                VALUES (?, 'provider-x', now() - interval '900 days', repeat('a', 64), 4, 'k2026a', now() - interval '900 days')""", id);
        // 2,500 expired UKX rows plus the newest SPX row (also expired but the only one for its symbol and source).
        jdbc.update("""
                INSERT INTO market_index_observation (symbol, source, observed_at, observed_at_source, value, batch_id)
                SELECT 'UKX', 'provider-x', now() - interval '800 days' - make_interval(mins => g), 'PROVIDER', 1, ?
                FROM generate_series(0, 2499) g""", id);
        jdbc.update("""
                INSERT INTO market_index_observation (symbol, source, observed_at, observed_at_source, value, batch_id)
                VALUES ('SPX', 'provider-x', now() - interval '801 days', 'PROVIDER', 1, ?)""", id);
        UUID orphan = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO market_index_ingest_batch (batch_id, source, collected_at, request_sha256, observation_count, credential_id, received_at)
                VALUES (?, 'provider-x', now() - interval '900 days', repeat('b', 64), 1, 'k2026a', now() - interval '900 days')""", orphan);

        var retention = new MarketIndexRetention(store, properties, Clock.fixed(Instant.now(), ZoneOffset.UTC));
        var result = retention.run(); // no upload in flight: retention is independent of ingestion
        assertThat(result.complete()).isTrue();
        assertThat(result.observations()).isEqualTo(2499); // every UKX row but the newest
        assertThat(result.batches()).isEqualTo(1); // only the orphan; the other batch is still referenced
        assertThat(jdbc.queryForList("SELECT symbol FROM market_index_observation ORDER BY symbol", String.class)).containsExactly("SPX", "UKX");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM market_index_ingest_batch WHERE batch_id = ?", Integer.class, orphan)).isZero();
        // Chunks are bounded.
        assertThat(store.deleteObservationsBefore(Instant.now(), MarketIndexRetention.CHUNK)).isZero();
    }

    @Test
    void ingestionDoesNotRunRetention() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO market_index_ingest_batch (batch_id, source, collected_at, request_sha256, observation_count, credential_id, received_at)
                VALUES (?, 'provider-x', now() - interval '900 days', repeat('a', 64), 2, 'k2026a', now() - interval '900 days')""", id);
        jdbc.update("""
                INSERT INTO market_index_observation (symbol, source, observed_at, observed_at_source, value, batch_id)
                VALUES ('UKX', 'provider-x', now() - interval '802 days', 'PROVIDER', 1, ?), ('UKX', 'provider-x', now() - interval '801 days', 'PROVIDER', 1, ?)""", id, id);
        assertThat(ingest(batch(UUID.randomUUID(), "provider-x", now(), obs("UKX", "8000.00", now().minusSeconds(10), "PROVIDER"))).statusCode()).isEqualTo(200);
        assertThat(count("market_index_observation")).isEqualTo(3);
    }

    // ---------------------------------------------------------------- crypto (V18)

    static String crypto(String symbol, String value, Instant observedAt, String reference) {
        return "{\"symbol\":\"" + symbol + "\",\"value\":\"" + value + "\",\"observedAt\":\"" + observedAt
                + "\",\"observedAtSource\":\"PROVIDER\"" + (reference == null ? ""
                : ",\"reference24h\":\"" + reference + "\",\"reference24hAt\":\"" + observedAt.minus(Duration.ofHours(24)) + "\"") + "}";
    }

    @Test
    void sixInstrumentsInOneBatchAndTheCryptoRollingChange() throws Exception {
        Instant at = now().truncatedTo(ChronoUnit.MINUTES).minusSeconds(60);
        var body = batch(UUID.randomUUID(), "provider-x", now(),
                obs("UKX", "101.00", at, "PROVIDER"), obs("SPX", "102.00", at, "PROVIDER"), obs("IXIC", "103.00", at, "PROVIDER"),
                obs("DJI", "104.00", at, "PROVIDER"), crypto("BTCUSD", "57885.27", at, "61784.82"), crypto("ETHUSD", "1640.18", at, null));
        var response = ingest(body);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(count("market_index_observation")).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT observation_count FROM market_index_ingest_batch", Integer.class)).isEqualTo(6);

        var latest = latest();
        assertThat(latest.get("indices")).extracting(n -> n.get("symbol").stringValue())
                .containsExactly("UKX", "SPX", "IXIC", "DJI", "BTCUSD", "ETHUSD");
        var btc = index(latest, "BTCUSD");
        assertThat(btc.get("name").stringValue()).isEqualTo("Bitcoin");
        assertThat(btc.get("assetClass").stringValue()).isEqualTo("CRYPTO");
        assertThat(btc.get("currency").stringValue()).isEqualTo("USD");
        assertThat(btc.get("changeBasis").stringValue()).isEqualTo("ROLLING_24H");
        assertThat(btc.get("value").stringValue()).isEqualTo("57885.2700");
        assertThat(btc.get("reference24h").stringValue()).isEqualTo("61784.8200");
        assertThat(Instant.parse(btc.get("reference24hAt").stringValue())).isEqualTo(at.minus(Duration.ofHours(24)));
        assertThat(btc.get("previousClose").isNull()).isTrue();
        assertThat(btc.get("change").stringValue()).isEqualTo("-3899.55");
        assertThat(btc.get("changePercent").stringValue()).isEqualTo("-6.31");
        assertThat(btc.get("market").get("exchange").stringValue()).isEqualTo("CRYPTO_24_7");
        assertThat(btc.get("market").get("session").stringValue()).isEqualTo("OPEN");
        assertThat(btc.get("freshness").get("status").stringValue()).isEqualTo("FRESH");
        // No verified window: the price only, never a change.
        var eth = index(latest, "ETHUSD");
        assertThat(eth.get("value").stringValue()).isEqualTo("1640.1800");
        assertThat(eth.get("change").isNull()).isTrue();
        assertThat(eth.get("changePercent").isNull()).isTrue();
        assertThat(eth.get("reference24h").isNull()).isTrue();
        // The indices keep their previous-close semantics.
        var ukx = index(latest, "UKX");
        assertThat(ukx.get("assetClass").stringValue()).isEqualTo("INDEX");
        assertThat(ukx.get("currency").isNull()).isTrue();
        assertThat(ukx.get("changeBasis").stringValue()).isEqualTo("PREVIOUS_CLOSE");
        assertThat(ukx.get("previousClose").stringValue()).isEqualTo("100.0000");
        assertThat(ukx.get("changePercent").stringValue()).isEqualTo("1.00");
        assertThat(ukx.get("market").get("exchange").stringValue()).isEqualTo("LSE");

        // An identical retry is a replay; a later different reference for the same instant is a correction.
        assertThat(json.readTree(ingest(body).body()).get("replay").booleanValue()).isTrue();
        var correction = ingest(batch(UUID.randomUUID(), "provider-x", now(), crypto("BTCUSD", "57885.27", at, "61784.83")));
        assertThat(correction.statusCode()).isEqualTo(200);
        assertThat(json.readTree(correction.body()).get("observations").get(0).get("result").stringValue()).isEqualTo("CORRECTED");
        assertThat(index(latest(), "BTCUSD").get("reference24h").stringValue()).isEqualTo("61784.8300");
        var same = ingest(batch(UUID.randomUUID(), "provider-x", now(), crypto("BTCUSD", "57885.27", at, "61784.83")));
        assertThat(json.readTree(same.body()).get("observations").get(0).get("result").stringValue()).isEqualTo("DUPLICATE");
    }

    static String instrument(String symbol, String status, int failures, String code, Instant lastAttempt, Instant lastSuccess) {
        return "{\"symbol\":\"" + symbol + "\",\"status\":\"" + status + "\",\"lastAttemptAt\":\"" + lastAttempt
                + "\",\"consecutiveFailures\":" + failures
                + (lastSuccess == null ? "" : ",\"lastSuccessfulCollectionAt\":\"" + lastSuccess + "\"")
                + (code == null ? "" : ",\"failureCode\":\"" + code + "\"") + "}";
    }

    static String withInstruments(String heartbeat, String... entries) {
        return heartbeat.substring(0, heartbeat.length() - 1) + ",\"instruments\":[" + String.join(",", entries) + "]}";
    }

    JsonNode instrumentStatus(JsonNode latest, String symbol) {
        for (JsonNode i : latest.get("collector").get("instruments")) if (i.get("symbol").stringValue().equals(symbol)) return i;
        throw new AssertionError(symbol);
    }

    @Test
    void cryptoHealthIsTrackedPerPairWithoutChangingTheIndexStatus() throws Exception {
        Instant at = now().truncatedTo(ChronoUnit.MINUTES).minusSeconds(60);
        assertThat(ingest(batch(UUID.randomUUID(), "provider-x", now(), obs("UKX", "101.00", at, "PROVIDER"),
                crypto("BTCUSD", "57885.27", at, null), crypto("ETHUSD", "1640.18", at, null))).statusCode()).isEqualTo(200);

        // A four-index heartbeat (crypto off, or a collector before crypto): the indices are HEALTHY as always, and the
        // pairs' own health is UNKNOWN, never borrowed from the indices.
        Instant t0 = now();
        assertThat(beat(heartbeat("provider-x", "OK", null, t0)).statusCode()).isEqualTo(200);
        var latest = latest();
        assertThat(sourceStatus(latest, "provider-x")).isEqualTo("HEALTHY");
        assertThat(index(latest, "UKX").get("collectorStatus").stringValue()).isEqualTo("HEALTHY");
        assertThat(index(latest, "BTCUSD").get("collectorStatus").stringValue()).isEqualTo("UNKNOWN");
        assertThat(instrumentStatus(latest, "BTCUSD").get("status").stringValue()).isEqualTo("UNKNOWN");

        // BTC/USD failing (third attempt in a row); ETH/USD and the indices fine.
        Instant t1 = t0.plusMillis(5);
        Instant btcSuccess = t1.minusSeconds(361);
        assertThat(beat(withInstruments(heartbeat("provider-x", "OK", null, t1),
                instrument("BTCUSD", "FAILED", 3, "UPSTREAM_TIMEOUT", t1.minusSeconds(1), btcSuccess),
                instrument("ETHUSD", "OK", 0, null, t1.minusSeconds(1), t1.minusSeconds(1)))).statusCode()).isEqualTo(200);
        latest = latest();
        assertThat(sourceStatus(latest, "provider-x")).isEqualTo("HEALTHY");
        assertThat(index(latest, "UKX").get("collectorStatus").stringValue()).isEqualTo("HEALTHY");
        assertThat(index(latest, "BTCUSD").get("collectorStatus").stringValue()).isEqualTo("PROVIDER_FAILED");
        assertThat(index(latest, "ETHUSD").get("collectorStatus").stringValue()).isEqualTo("HEALTHY");
        var btc = instrumentStatus(latest, "BTCUSD");
        assertThat(btc.get("source").stringValue()).isEqualTo("provider-x");
        assertThat(btc.get("consecutiveFailures").intValue()).isEqualTo(3);
        assertThat(btc.get("failureCode").stringValue()).isEqualTo("UPSTREAM_TIMEOUT");
        assertThat(Instant.parse(btc.get("lastSuccessfulCollectionAt").stringValue())).isEqualTo(btcSuccess);
        assertThat(btc.get("freshness").stringValue()).isEqualTo("FRESH");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM market_index_instrument_health", Integer.class)).isEqualTo(2);

        // An older report never overwrites the newer per-pair health either.
        var older = beat(withInstruments(heartbeat("provider-x", "OK", null, t0.minusSeconds(30)),
                instrument("BTCUSD", "OK", 0, null, t0.minusSeconds(31), t0.minusSeconds(31))));
        assertThat(json.readTree(older.body()).get("applied").booleanValue()).isFalse();
        assertThat(instrumentStatus(latest(), "BTCUSD").get("status").stringValue()).isEqualTo("PROVIDER_FAILED");

        // Recovery: the next success resets the streak.
        Instant t2 = t1.plusMillis(5);
        assertThat(beat(withInstruments(heartbeat("provider-x", "OK", null, t2),
                instrument("BTCUSD", "OK", 0, null, t2.minusSeconds(1), t2.minusSeconds(1)),
                instrument("ETHUSD", "OK", 0, null, t2.minusSeconds(1), t2.minusSeconds(1)))).statusCode()).isEqualTo(200);
        btc = instrumentStatus(latest(), "BTCUSD");
        assertThat(btc.get("status").stringValue()).isEqualTo("HEALTHY");
        assertThat(btc.get("consecutiveFailures").intValue()).isZero();
        assertThat(btc.get("failureCode").isNull()).isTrue();

        // Never collected: NO_SUCCESSFUL_COLLECTION. The index failure leaves ETH/USD healthy.
        Instant t3 = t2.plusMillis(5);
        assertThat(beat(withInstruments(heartbeat("provider-x", "PROVIDER_FAILED", "UPSTREAM_UNAVAILABLE", t3),
                instrument("ETHUSD", "OK", 0, null, t3.minusSeconds(1), t3.minusSeconds(1)))).statusCode()).isEqualTo(200);
        latest = latest();
        assertThat(index(latest, "UKX").get("collectorStatus").stringValue()).isEqualTo("PROVIDER_FAILED");
        assertThat(instrumentStatus(latest, "ETHUSD").get("status").stringValue()).isEqualTo("HEALTHY");
        // BTC/USD is no longer reported in that heartbeat: its health is unknown, not carried over.
        assertThat(instrumentStatus(latest, "BTCUSD").get("status").stringValue()).isEqualTo("UNKNOWN");

        // A pair whose last attempt is older than the heartbeat timeout: stalled. A silent collector: disconnected.
        Instant t4 = t3.plusMillis(5);
        assertThat(beat(withInstruments(heartbeat("provider-x", "OK", null, t4),
                instrument("BTCUSD", "FAILED", 1, "X", t4.minusSeconds(1), null),
                instrument("ETHUSD", "OK", 0, null, t4.minusSeconds(400), t4.minusSeconds(400)))).statusCode()).isEqualTo(200);
        latest = latest();
        assertThat(instrumentStatus(latest, "BTCUSD").get("status").stringValue()).isEqualTo("NO_SUCCESSFUL_COLLECTION");
        assertThat(instrumentStatus(latest, "ETHUSD").get("status").stringValue()).isEqualTo("COLLECTION_STALLED");
        // Rows left by an older heartbeat (an image without per-pair health applied a newer one) are not current.
        jdbc.update("UPDATE market_index_collector_heartbeat SET sent_at = sent_at + interval '1 second'");
        latest = latest();
        assertThat(instrumentStatus(latest, "ETHUSD").get("status").stringValue()).isEqualTo("UNKNOWN");
        assertThat(instrumentStatus(latest, "ETHUSD").get("consecutiveFailures").isNull()).isTrue();
        assertThat(index(latest, "ETHUSD").get("collectorStatus").stringValue()).isEqualTo("UNKNOWN");
        jdbc.update("UPDATE market_index_collector_heartbeat SET received_at = now() - interval '7 minutes'");
        latest = latest();
        assertThat(instrumentStatus(latest, "BTCUSD").get("status").stringValue()).isEqualTo("DISCONNECTED");
        assertThat(instrumentStatus(latest, "ETHUSD").get("status").stringValue()).isEqualTo("DISCONNECTED");
    }

    @Test
    void cryptoMonitoringReportsStaleAndUnavailableData() throws Exception {
        Instant old = now().truncatedTo(ChronoUnit.MINUTES).minus(Duration.ofHours(2));
        assertThat(ingest(batch(UUID.randomUUID(), "provider-x", now(), crypto("BTCUSD", "57885.27", old, null))).statusCode())
                .isEqualTo(200);
        Instant t = now();
        assertThat(beat(withInstruments(heartbeat("provider-x", "OK", null, t),
                instrument("BTCUSD", "FAILED", 60, "UPSTREAM_TIMEOUT", t.minusSeconds(1), old.plusSeconds(30)))).statusCode())
                .isEqualTo(200);
        var latest = latest();
        var btc = instrumentStatus(latest, "BTCUSD");
        assertThat(btc.get("status").stringValue()).isEqualTo("PROVIDER_FAILED");
        assertThat(btc.get("freshness").stringValue()).isEqualTo("STALE");
        assertThat(index(latest, "BTCUSD").get("freshness").get("status").stringValue()).isEqualTo("STALE");
        var eth = instrumentStatus(latest, "ETHUSD");
        assertThat(eth.get("status").stringValue()).isEqualTo("UNKNOWN");
        assertThat(eth.get("freshness").stringValue()).isEqualTo("UNAVAILABLE");
        assertThat(eth.get("consecutiveFailures").isNull()).isTrue();
    }

    @Test
    void cryptoIngestionDoesNotDependOnTheStockIndices() throws Exception {
        Instant at = now().truncatedTo(ChronoUnit.MINUTES).minusSeconds(60);
        assertThat(ingest(batch(UUID.randomUUID(), "provider-x", now(), crypto("BTCUSD", "57885.27", at, null))).statusCode()).isEqualTo(200);
        var latest = latest();
        assertThat(index(latest, "BTCUSD").get("value").stringValue()).isEqualTo("57885.2700");
        assertThat(index(latest, "UKX").get("value").isNull()).isTrue();
        assertThat(index(latest, "UKX").get("freshness").get("status").stringValue()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void theDatabaseRefusesAWrongWindowOrMixedSemantics() throws Exception {
        Instant at = now().truncatedTo(ChronoUnit.MINUTES).minusSeconds(60);
        assertThat(ingest(batch(UUID.randomUUID(), "provider-x", now(), crypto("ETHUSD", "1640.18", at, null))).statusCode()).isEqualTo(200);
        UUID batchId = jdbc.queryForObject("SELECT batch_id FROM market_index_ingest_batch", UUID.class);
        String insert = "INSERT INTO market_index_observation (symbol, source, observed_at, observed_at_source, value, previous_close, "
                + "batch_id, reference_24h, reference_24h_at) VALUES (?, 'provider-x', ?, 'PROVIDER', 1, ?, ?, ?, ?)";
        java.sql.Timestamp t = java.sql.Timestamp.from(at.minusSeconds(600));
        for (Object[] row : new Object[][] {
                {"BTCUSD", null, new java.math.BigDecimal("2"), java.sql.Timestamp.from(at.minusSeconds(600).minus(Duration.ofHours(23)))},
                {"BTCUSD", new java.math.BigDecimal("2"), null, null},
                {"BTCUSD", null, new java.math.BigDecimal("2"), null},
                {"UKX", null, new java.math.BigDecimal("2"), java.sql.Timestamp.from(at.minusSeconds(600).minus(Duration.ofHours(24)))},
                {"XRPUSD", null, null, null}}) {
            assertThat(catchThrowable(() -> jdbc.update(insert, row[0], t, row[1], batchId, row[2], row[3])))
                    .as(java.util.Arrays.toString(row)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        jdbc.update(insert, "BTCUSD", t, null, batchId, new java.math.BigDecimal("2"),
                java.sql.Timestamp.from(at.minusSeconds(600).minus(Duration.ofHours(24))));
    }
}
