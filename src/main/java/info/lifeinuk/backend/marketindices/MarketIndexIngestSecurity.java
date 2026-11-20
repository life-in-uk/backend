package info.lifeinuk.backend.marketindices;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Dedicated collector credential for {@code /api/internal/market-indices/**} only, separate from website sessions and
 * every editorial credential. The header {@code X-Market-Collector-Token: <keyId>.<secret>} is checked against
 * configured SHA-256 hashes with constant-time comparison over every configured key; the server never holds the
 * secret. Each key is bound to the source ids it may write:
 * {@code MARKET_INDICES_COLLECTOR_KEYS=keyId:sha256hex:source[|source][,...]} (at most two keys, for rotation).
 * A key may only submit observations and heartbeats for its own sources (403 SOURCE_NOT_AUTHORIZED otherwise), so a
 * mock-only credential can never write a licensed source. No key configured: 503 MARKET_INGEST_DISABLED. The
 * header value is never logged or echoed.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MarketIndexProperties.class)
class MarketIndexIngestSecurity implements WebMvcConfigurer {
    static final String HEADER = "X-Market-Collector-Token";
    static final String CREDENTIAL_ATTRIBUTE = MarketIndexIngestSecurity.class.getName() + ".credential";
    static final String PATH = "/api/internal/market-indices/**";
    private static final Pattern KEY_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,31}$");
    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");
    /** Editorial and owner credentials that must never double as the collector credential. */
    private static final List<String> OTHER_CREDENTIALS = List.of(
            "life-in-uk.editorial.draft-token", "life-in-uk.editorial.review-token",
            "life-in-uk.editorial.mock-review-token", "life-in-uk.editorial.approval-token",
            "life-in-uk.editorial.local-review-token", "life-in-uk.editorial.owner-draft-password",
            "life-in-uk.editorial.owner-approval-password");

    /** A configured key: id, SHA-256 of the secret and the sources it may write. */
    record Key(String id, byte[] hash, java.util.Set<String> sources) {
        boolean authorizes(String source) { return sources.contains(source); }
    }

    private final List<Key> keys;

    MarketIndexIngestSecurity(MarketIndexProperties properties, Environment environment) {
        this.keys = parse(properties.collectorKeys());
        for (String name : OTHER_CREDENTIALS) {
            String other = environment.getProperty(name, "");
            if (other.isEmpty()) continue;
            byte[] otherHash = sha256(other);
            for (Key key : keys) {
                if (MessageDigest.isEqual(key.hash(), otherHash)) {
                    throw new IllegalArgumentException("The market index collector credential must differ from every editorial credential");
                }
            }
        }
    }

    static List<Key> parse(String configured) {
        var parsed = new ArrayList<Key>();
        if (configured == null || configured.isBlank()) return List.of();
        for (String entry : configured.split(",")) {
            String[] parts = entry.strip().split(":", -1);
            if (parts.length != 3 || !KEY_ID.matcher(parts[0]).matches() || !HASH.matcher(parts[1]).matches()) {
                throw new IllegalArgumentException("MARKET_INDICES_COLLECTOR_KEYS entries must be keyId:sha256hex:source[|source]");
            }
            var sources = new java.util.LinkedHashSet<String>();
            for (String source : parts[2].split("\\|", -1)) {
                if (!MarketIndexProperties.SOURCE.matcher(source).matches() || !sources.add(source)) {
                    throw new IllegalArgumentException("Each collector key needs one or more distinct, valid source ids");
                }
            }
            if (parsed.stream().anyMatch(k -> k.id().equals(parts[0]))) throw new IllegalArgumentException("Duplicate collector key id");
            parsed.add(new Key(parts[0], HexFormat.of().parseHex(parts[1]), java.util.Set.copyOf(sources)));
        }
        if (parsed.size() > 2) throw new IllegalArgumentException("At most two collector keys (current and next)");
        if (parsed.size() == 2 && MessageDigest.isEqual(parsed.get(0).hash(), parsed.get(1).hash())) {
            throw new IllegalArgumentException("Collector keys must be distinct");
        }
        return List.copyOf(parsed);
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    boolean configured() { return !keys.isEmpty(); }

    /** The matching key, or null. Every configured key is compared, so timing does not reveal which one matched. */
    Key authenticate(String header) {
        if (header == null || header.length() > 300) return null;
        int dot = header.indexOf('.');
        if (dot <= 0) return null;
        String id = header.substring(0, dot);
        String secret = header.substring(dot + 1);
        if (secret.length() < 32) return null;
        byte[] supplied = sha256(secret);
        Key matched = null;
        for (Key key : keys) {
            boolean equal = MessageDigest.isEqual(key.hash(), supplied);
            if (equal && key.id().equals(id)) matched = key;
        }
        return matched;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                if (!configured()) {
                    return reject(response, HttpStatus.SERVICE_UNAVAILABLE, "MARKET_INGEST_DISABLED", "Market index ingestion is not configured.");
                }
                Key credential = authenticate(request.getHeader(HEADER));
                if (credential == null) {
                    return reject(response, HttpStatus.UNAUTHORIZED, "COLLECTOR_UNAUTHORIZED", "A valid collector credential is required.");
                }
                request.setAttribute(CREDENTIAL_ATTRIBUTE, credential);
                return true;
            }
        }).addPathPatterns(PATH);
    }

    private static boolean reject(HttpServletResponse response, HttpStatus status, String code, String message) throws java.io.IOException {
        response.setStatus(status.value());
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
        return false;
    }
}
