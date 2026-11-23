package info.lifeinuk.backend.marketindices;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Strict parsing and validation of collector requests. Structural problems are 400 MARKET_BATCH_INVALID (or
 * HEARTBEAT_INVALID); well-formed but unacceptable content is 422 with a specific code. Nothing here touches the
 * database, and no rejected value is echoed back beyond the field path.
 */
final class MarketIndexPayloads {
    private MarketIndexPayloads() { }

    static final int MAX_BODY_BYTES = 4096;
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Pattern DECIMAL = Pattern.compile("^[0-9]{1,8}(\\.[0-9]{1,4})?$");
    /** UTC or explicit offset, at most microsecond precision (what PostgreSQL stores): nothing is silently truncated. */
    private static final Pattern TIMESTAMP = Pattern.compile(
            "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?(Z|[+-][0-9]{2}:[0-9]{2})$");
    private static final Pattern UUID_TEXT = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern FAILURE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,39}$");

    /** A rejected request: HTTP status, stable code and a message naming at most the field path. */
    static final class Rejected extends RuntimeException {
        final HttpStatus status;
        final String code;

        Rejected(HttpStatus status, String code, String message) {
            super(message, null, false, false);
            this.status = status;
            this.code = code;
        }
    }

    /**
     * {@code previousClose}: stock indices only. {@code reference24h}/{@code reference24hAt}: crypto only, together,
     * with reference24hAt exactly 24 hours before observedAt; absent when the collector had no verified window.
     */
    record Observation(MarketIndexSymbol symbol, BigDecimal value, BigDecimal previousClose, Instant observedAt,
            MarketIndexSymbol.TimeSource timeSource, BigDecimal reference24h, Instant reference24hAt) {
        Observation(MarketIndexSymbol symbol, BigDecimal value, BigDecimal previousClose, Instant observedAt,
                MarketIndexSymbol.TimeSource timeSource) {
            this(symbol, value, previousClose, observedAt, timeSource, null, null);
        }
    }

    static final java.time.Duration ROLLING_WINDOW = java.time.Duration.ofHours(24);

    record Batch(UUID batchId, String source, Instant collectedAt, List<Observation> observations, String requestSha256) { }

    enum CollectorStatus { OK, PROVIDER_FAILED }

    record Heartbeat(String source, CollectorStatus status, String providerFailureCode, Instant sentAt,
            Instant lastAttemptAt, Instant lastSuccessfulCollectionAt, int pendingBatches,
            Instant oldestPendingCollectedAt) { }

    // ---------------------------------------------------------------- observations

    /**
     * Structural parsing plus content invariants that never change with time (mock data must carry a COLLECTOR time).
     * Source authorization ({@link #requireAuthorized}) and the time window ({@link #requireWithinWindow}) are
     * separate steps: an identical retry of an already committed batch is acknowledged as a replay even after its
     * observations have crossed the backfill cutoff, but never without authentication and source authorization.
     */
    static Batch batch(byte[] body) {
        final String invalid = "MARKET_BATCH_INVALID";
        JsonNode root = parse(body, invalid);
        fields(root, "", Set.of("schemaVersion", "batchId", "source", "collectedAt", "observations"), Set.of(), invalid);
        schemaVersion(root, invalid);
        String batchText = text(root, "batchId", invalid);
        if (!UUID_TEXT.matcher(batchText).matches()) throw bad(invalid, "batchId must be a lowercase UUID.");
        UUID batchId = UUID.fromString(batchText);
        String source = source(root, invalid);
        Instant collectedAt = timestamp(root, "collectedAt", invalid);
        JsonNode list = root.get("observations");
        if (list == null || !list.isArray() || list.isEmpty() || list.size() > MarketIndexSymbol.values().length) {
            throw bad(invalid, "observations must be an array of 1 to " + MarketIndexSymbol.values().length + " items.");
        }
        var observations = new ArrayList<Observation>();
        var seen = EnumSet.noneOf(MarketIndexSymbol.class);
        for (int i = 0; i < list.size(); i++) {
            String at = "observations[" + i + "]";
            JsonNode item = list.get(i);
            fields(item, at + ".", Set.of("symbol", "value", "observedAt", "observedAtSource"),
                    Set.of("previousClose", "reference24h", "reference24hAt"), invalid);
            var symbol = MarketIndexSymbol.parse(text(item, "symbol", at + ".", invalid))
                    .orElseThrow(() -> bad(invalid, at + ".symbol must be one of " + MarketIndexSymbol.NAMES + "."));
            if (!seen.add(symbol)) throw bad(invalid, at + ".symbol is repeated.");
            BigDecimal value = decimal(item, "value", at + ".", invalid);
            BigDecimal previousClose = item.has("previousClose") && !item.get("previousClose").isNull()
                    ? decimal(item, "previousClose", at + ".", invalid) : null;
            Instant observedAt = timestamp(item, "observedAt", at + ".", invalid);
            var timeSource = MarketIndexSymbol.TimeSource.parse(text(item, "observedAtSource", at + ".", invalid))
                    .orElseThrow(() -> bad(invalid, at + ".observedAtSource must be EXCHANGE, PROVIDER or COLLECTOR."));
            boolean hasReference = present(item, "reference24h");
            boolean hasReferenceAt = present(item, "reference24hAt");
            BigDecimal reference24h = null;
            Instant reference24hAt = null;
            if (symbol.crypto()) {
                if (previousClose != null) throw bad(invalid, at + ".previousClose is not used for " + symbol + "; send reference24h.");
                if (hasReference != hasReferenceAt) throw bad(invalid, at + ".reference24h and reference24hAt must be sent together.");
                if (hasReference) {
                    reference24h = decimal(item, "reference24h", at + ".", invalid);
                    reference24hAt = timestamp(item, "reference24hAt", at + ".", invalid);
                    if (!reference24hAt.equals(observedAt.minus(ROLLING_WINDOW))) {
                        throw bad(invalid, at + ".reference24hAt must be exactly 24 hours before observedAt.");
                    }
                }
            } else if (hasReference || hasReferenceAt) {
                throw bad(invalid, at + ".reference24h is only used for BTCUSD and ETHUSD.");
            }
            observations.add(new Observation(symbol, value, previousClose, observedAt, timeSource, reference24h, reference24hAt));
        }
        observations.sort(Comparator.comparing(Observation::symbol));
        for (var o : observations) {
            if (MarketIndexProperties.MOCK_SOURCE.equals(source) && o.timeSource() != MarketIndexSymbol.TimeSource.COLLECTOR) {
                throw unprocessable("MOCK_TIME_SOURCE_INVALID", "observations[" + o.symbol() + "].observedAtSource must be COLLECTOR for mock data.");
            }
        }
        return new Batch(batchId, source, collectedAt, List.copyOf(observations), canonicalHash(batchId, source, collectedAt, observations));
    }

    /** The source must be accepted by this server (422) and bound to the authenticated collector key (403). */
    static void requireAuthorized(String source, java.util.Set<String> keySources, MarketIndexProperties properties) {
        if (!properties.acceptedSources().contains(source)) throw unprocessable("SOURCE_NOT_ACCEPTED", "source is not accepted by this server.");
        if (keySources == null || !keySources.contains(source)) {
            throw new Rejected(HttpStatus.FORBIDDEN, "SOURCE_NOT_AUTHORIZED", "This collector credential may not write this source.");
        }
    }

    /** Time-window rules for a batch that is not already stored (422). */
    static void requireWithinWindow(Batch batch, Instant now, MarketIndexProperties properties) {
        if (batch.collectedAt().isAfter(now.plus(properties.collectorClockSkew()))) {
            throw unprocessable("COLLECTOR_CLOCK_SKEW", "collectedAt is ahead of server time; check the collector clock.");
        }
        Instant collectedAt = batch.collectedAt();
        for (var o : batch.observations()) {
            String at = "observations[" + o.symbol() + "]";
            if (o.observedAt().isAfter(now.plus(properties.futureSkew()))) {
                throw unprocessable("FUTURE_OBSERVATION", at + ".observedAt is ahead of server time.");
            }
            if (o.observedAt().isAfter(collectedAt.plus(properties.futureSkew()))) {
                throw unprocessable("OBSERVATION_AFTER_COLLECTION", at + ".observedAt is later than collectedAt.");
            }
            if (o.observedAt().isBefore(now.minus(properties.maxBackfill()))) {
                throw unprocessable("OBSERVATION_TOO_OLD", at + ".observedAt is older than the backfill window.");
            }
        }
    }

    /**
     * Hash of the normalised content: key order, whitespace and equivalent spellings of one value do not matter. A
     * stock index line is unchanged from V17 (stored hashes of earlier batches still match a replay); a crypto line
     * adds its 24-hour reference.
     */
    static String canonicalHash(UUID batchId, String source, Instant collectedAt, List<Observation> sorted) {
        var text = new StringBuilder("market-index-batch-v1\n").append(batchId).append('\n').append(source).append('\n')
                .append(collectedAt).append('\n');
        for (var o : sorted) {
            text.append(o.symbol()).append('|').append(o.value().toPlainString()).append('|')
                    .append(o.previousClose() == null ? "-" : o.previousClose().toPlainString()).append('|')
                    .append(o.observedAt()).append('|').append(o.timeSource());
            if (o.symbol().crypto()) {
                text.append('|').append(o.reference24h() == null ? "-" : o.reference24h().toPlainString())
                        .append('|').append(o.reference24hAt() == null ? "-" : o.reference24hAt().toString());
            }
            text.append('\n');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    // ---------------------------------------------------------------- heartbeat

    static Heartbeat heartbeat(byte[] body, Instant now, MarketIndexProperties properties) {
        final String invalid = "HEARTBEAT_INVALID";
        JsonNode root = parse(body, invalid);
        fields(root, "", Set.of("schemaVersion", "source", "sentAt", "collectorStatus", "lastAttemptAt", "pendingBatches"),
                Set.of("providerFailureCode", "lastSuccessfulCollectionAt", "oldestPendingCollectedAt"), invalid);
        schemaVersion(root, invalid);
        String source = source(root, invalid);
        Instant sentAt = timestamp(root, "sentAt", invalid);
        CollectorStatus status;
        try {
            status = CollectorStatus.valueOf(text(root, "collectorStatus", invalid));
        } catch (IllegalArgumentException unknown) {
            throw bad(invalid, "collectorStatus must be OK or PROVIDER_FAILED.");
        }
        String failure = optionalText(root, "providerFailureCode", invalid);
        if (failure != null && !FAILURE_CODE.matcher(failure).matches()) {
            throw bad(invalid, "providerFailureCode must be an upper-case code of at most 40 characters.");
        }
        if ((status == CollectorStatus.PROVIDER_FAILED) != (failure != null)) {
            throw bad(invalid, "providerFailureCode is required exactly when collectorStatus is PROVIDER_FAILED.");
        }
        Instant lastAttemptAt = timestamp(root, "lastAttemptAt", invalid);
        Instant lastSuccess = optionalTimestamp(root, "lastSuccessfulCollectionAt", invalid);
        Instant oldestPending = optionalTimestamp(root, "oldestPendingCollectedAt", invalid);
        JsonNode pending = root.get("pendingBatches");
        if (pending == null || !pending.isIntegralNumber() || !pending.canConvertToInt()
                || pending.intValue() < 0 || pending.intValue() > 1_000_000) {
            throw bad(invalid, "pendingBatches must be an integer from 0 to 1000000.");
        }
        // OK means the last attempt was a successful collection; PROVIDER_FAILED means it was not.
        if (status == CollectorStatus.OK && (lastSuccess == null || lastSuccess.isBefore(lastAttemptAt))) {
            throw bad(invalid, "collectorStatus OK requires lastSuccessfulCollectionAt at or after lastAttemptAt.");
        }
        if (status == CollectorStatus.PROVIDER_FAILED && lastSuccess != null && !lastSuccess.isBefore(lastAttemptAt)) {
            throw bad(invalid, "collectorStatus PROVIDER_FAILED requires the last attempt to be later than the last success.");
        }
        // Heartbeats report the present; they are never queued and replayed later.
        if (sentAt.isAfter(now.plus(properties.collectorClockSkew())) || sentAt.isBefore(now.minus(properties.collectorClockSkew()))) {
            throw unprocessable("HEARTBEAT_CLOCK_SKEW", "sentAt is too far from server time; heartbeats are not replayed.");
        }
        for (var entry : new Object[][] {{"lastAttemptAt", lastAttemptAt}, {"lastSuccessfulCollectionAt", lastSuccess},
                {"oldestPendingCollectedAt", oldestPending}}) {
            if (entry[1] != null && ((Instant) entry[1]).isAfter(sentAt.plus(properties.futureSkew()))) {
                throw unprocessable("HEARTBEAT_TIME_INVALID", entry[0] + " is later than sentAt.");
            }
        }
        return new Heartbeat(source, status, failure, sentAt, lastAttemptAt, lastSuccess, pending.intValue(), oldestPending);
    }

    // ---------------------------------------------------------------- helpers

    private static JsonNode parse(byte[] body, String code) {
        if (body == null || body.length == 0) throw bad(code, "A JSON object is required.");
        try {
            JsonNode root = JSON.readTree(body);
            if (root == null || !root.isObject()) throw bad(code, "A JSON object is required.");
            return root;
        } catch (JacksonException malformed) {
            throw bad(code, "The body is not valid JSON.");
        }
    }

    private static void schemaVersion(JsonNode root, String code) {
        JsonNode version = root.get("schemaVersion");
        if (version == null || !version.isIntegralNumber() || version.asLong() != 1) throw bad(code, "schemaVersion must be 1.");
    }

    private static String source(JsonNode root, String code) {
        String source = text(root, "source", code);
        if (!MarketIndexProperties.SOURCE.matcher(source).matches()) throw bad(code, "source must be a lowercase identifier.");
        return source;
    }

    private static void fields(JsonNode node, String path, Set<String> required, Set<String> optional, String code) {
        if (node == null || !node.isObject()) throw bad(code, (path.isEmpty() ? "body" : path.substring(0, path.length() - 1)) + " must be an object.");
        for (String name : node.propertyNames()) {
            if (!required.contains(name) && !optional.contains(name)) throw bad(code, path + name + " is not a recognised field.");
        }
        for (String name : required) {
            if (!node.has(name) || node.get(name).isNull()) throw bad(code, path + name + " is required.");
        }
    }

    private static String text(JsonNode node, String name, String code) { return text(node, name, "", code); }

    private static String text(JsonNode node, String name, String path, String code) {
        JsonNode value = node.get(name);
        if (value == null || !value.isString() || value.stringValue().length() > 64) throw bad(code, path + name + " must be a string.");
        return value.stringValue();
    }

    private static boolean present(JsonNode node, String name) { return node.has(name) && !node.get(name).isNull(); }

    private static String optionalText(JsonNode node, String name, String code) {
        return node.has(name) && !node.get(name).isNull() ? text(node, name, code) : null;
    }

    private static BigDecimal decimal(JsonNode node, String name, String path, String code) {
        String raw = text(node, name, path, code);
        if (!DECIMAL.matcher(raw).matches()) {
            throw bad(code, path + name + " must be a decimal string with at most 8 integer and 4 fractional digits.");
        }
        BigDecimal value = new BigDecimal(raw).setScale(4);
        if (value.signum() <= 0) throw bad(code, path + name + " must be greater than zero.");
        return value;
    }

    private static Instant timestamp(JsonNode node, String name, String code) { return timestamp(node, name, "", code); }

    private static Instant timestamp(JsonNode node, String name, String path, String code) {
        String raw = text(node, name, path, code);
        if (!TIMESTAMP.matcher(raw).matches()) {
            throw bad(code, path + name + " must be an ISO 8601 timestamp with Z or an explicit offset.");
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException invalid) {
            throw bad(code, path + name + " is not a valid timestamp.");
        }
    }

    private static Instant optionalTimestamp(JsonNode node, String name, String code) {
        return node.has(name) && !node.get(name).isNull() ? timestamp(node, name, code) : null;
    }

    private static Rejected bad(String code, String message) { return new Rejected(HttpStatus.BAD_REQUEST, code, message); }

    private static Rejected unprocessable(String code, String message) { return new Rejected(HttpStatus.UNPROCESSABLE_CONTENT, code, message); }
}
