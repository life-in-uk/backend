package info.lifeinuk.backend.guides;

import java.time.Instant;
import java.util.List;

/** Validated local input only, separate from persistence and the public response contract. */
record GuideImportDefinition(String slug, String category, String title, String summary, String content,
        GuideStatus status, Instant publishedAt, Instant updatedAt, List<Source> sources, List<Evidence> evidence) {
    GuideImportDefinition(String slug, String category, String title, String summary, String content,
            GuideStatus status, Instant publishedAt, Instant updatedAt, List<Source> sources) {
        this(slug, category, title, summary, content, status, publishedAt, updatedAt, sources, null);
    }
    GuideImportDefinition {
        key("slug", slug, 160); key("category", category, 100);
        text("title", title, 200); text("summary", summary, 1000); text("content", content, Integer.MAX_VALUE);
        timestamp("updatedAt", updatedAt);
        if (publishedAt != null) { timestamp("publishedAt", publishedAt); }
        Guide.validatePublication(status, publishedAt, updatedAt);
        if (sources == null || sources.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("sources must be an array of complete source objects");
        }
        sources = List.copyOf(sources);
        var sourceKeys = new java.util.HashSet<String>();
        for (var source : sources) {
            if (source.key() != null && !sourceKeys.add(source.key())) {
                throw new IllegalArgumentException("Duplicate source key: " + source.key());
            }
        }
        if (evidence != null) { evidence = List.copyOf(evidence); }
        var evidenceKeys = new java.util.HashSet<String>();
        for (var item : evidence == null ? List.<Evidence>of() : evidence) {
            if (!evidenceKeys.add(item.key())) { throw new IllegalArgumentException("Duplicate evidence key: " + item.key()); }
            for (var support : item.supports()) {
                if (!sourceKeys.contains(support.sourceKey())) {
                    throw new IllegalArgumentException("Unknown support sourceKey: " + support.sourceKey());
                }
            }
        }
        GuideEvidenceReferences.validate(content, evidenceKeys);
    }
    record Source(String key, String organisation, String title, String url, Instant accessedAt) {
        Source(String organisation, String title, String url, Instant accessedAt) {
            this(null, organisation, title, url, accessedAt);
        }
        Source {
            if (key != null) {
                GuideImportDefinition.key("source.key", key, 160);
                try {
                    var uri = new java.net.URI(url);
                    if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                            || uri.getHost() == null || uri.getRawUserInfo() != null) {
                        throw new IllegalArgumentException("Keyed source URL requires absolute HTTP(S), without credentials");
                    }
                } catch (java.net.URISyntaxException | NullPointerException invalid) {
                    throw new IllegalArgumentException("Invalid keyed source URL", invalid);
                }
            }
            text("source.organisation", organisation, 200); text("source.title", title, 300);
            text("source.url", url, 2000); timestamp("source.accessedAt", accessedAt);
        }
    }
    record Evidence(String key, String statement, List<Support> supports) {
        Evidence {
            GuideImportDefinition.key("evidence.key", key, 160);
            text("evidence.statement", statement, 10000);
            if (supports == null || supports.isEmpty()) { throw new IllegalArgumentException("Evidence requires at least one support"); }
            supports = List.copyOf(supports);
        }
    }
    record Support(String sourceKey, String locator, String excerpt, String note) {
        Support {
            key("support.sourceKey", sourceKey, 160);
            if (locator != null) { text("support.locator", locator, 10000); }
            if (excerpt != null) { text("support.excerpt", excerpt, 20000); }
            if (locator == null && excerpt == null) { throw new IllegalArgumentException("Support requires locator or excerpt"); }
            text("support.note", note, 10000);
        }
    }
    private static void key(String field, String value, int max) {
        try { Guide.key(value, max); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(field + " must be a lower-case hyphenated key (max " + max + ")"); }
    }
    private static void text(String field, String value, int max) {
        try { Guide.text(value, max); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(field + " must be nonblank text (max " + max + ")"); }
    }
    private static void timestamp(String field, Instant value) {
        if (value == null || value.getNano() % 1000 != 0 || value.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                || value.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))) {
            throw new IllegalArgumentException(field + " requires a timestamp in years 0001–9999 with at most microsecond precision");
        }
    }
}
