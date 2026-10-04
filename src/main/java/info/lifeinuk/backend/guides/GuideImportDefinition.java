package info.lifeinuk.backend.guides;

import java.time.Instant;
import java.util.List;

/** Validated local input only, separate from persistence and the public response contract. */
record GuideImportDefinition(String slug, String category, String title, String summary, String content,
        GuideStatus status, Instant publishedAt, Instant updatedAt, List<Source> sources) {
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
    }
    record Source(String organisation, String title, String url, Instant accessedAt) {
        Source {
            text("source.organisation", organisation, 200); text("source.title", title, 300);
            text("source.url", url, 2000); timestamp("source.accessedAt", accessedAt);
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
