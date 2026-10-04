package info.lifeinuk.backend.guides;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One reviewed UTF-8 JSON document. No file scanning, URL access or persistence. */
@Component
class GuideImportReader {
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    GuideImportDefinition read(Path path) throws IOException {
        return parse(Files.readAllBytes(path));
    }

    GuideImportDefinition parse(byte[] bytes) {
        JsonNode root;
        try { root = json.readTree(bytes); }
        catch (JacksonException invalid) { throw new IllegalArgumentException("Import file must contain one valid JSON document", invalid); }
        fields(root, Set.of("slug", "category", "title", "summary", "content", "status", "publishedAt", "updatedAt", "sources"), "guide");
        GuideStatus status;
        try { status = GuideStatus.valueOf(text(root, "status")); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("status must be DRAFT or PUBLISHED"); }
        var inputSources = root.get("sources");
        if (inputSources == null || !inputSources.isArray()) { throw new IllegalArgumentException("sources must be an array (empty is allowed)"); }
        var sources = new ArrayList<GuideImportDefinition.Source>();
        for (int index = 0; index < inputSources.size(); index++) {
            try {
                var source = inputSources.get(index);
                fields(source, Set.of("organisation", "title", "url", "accessedAt"), "source");
                sources.add(new GuideImportDefinition.Source(text(source, "organisation"), text(source, "title"),
                        text(source, "url"), time(source, "accessedAt")));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("sources[" + index + "]: " + invalid.getMessage(), invalid);
            }
        }
        var published = root.get("publishedAt");
        return new GuideImportDefinition(text(root, "slug"), text(root, "category"), text(root, "title"),
                text(root, "summary"), text(root, "content"), status,
                published == null || published.isNull() ? null : time(root, "publishedAt"), time(root, "updatedAt"), sources);
    }

    private static void fields(JsonNode node, Set<String> allowed, String label) {
        if (node == null || !node.isObject()) { throw new IllegalArgumentException(label + " must be an object"); }
        for (var property : node.properties()) {
            if (!allowed.contains(property.getKey())) { throw new IllegalArgumentException(label + " contains unsupported field: " + property.getKey()); }
        }
    }
    private static String text(JsonNode node, String name) {
        var value = node.get(name);
        if (value == null || !value.isString()) { throw new IllegalArgumentException(name + " must be a string"); }
        return value.stringValue();
    }
    private static Instant time(JsonNode node, String name) {
        String value = text(node, name);
        if (!value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?(?:Z|[+-]\\d{2}:\\d{2})")) {
            throw new IllegalArgumentException(name + " must be an offset-aware ISO timestamp with at most six fractional digits");
        }
        try { return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant(); }
        catch (DateTimeParseException invalid) { throw new IllegalArgumentException(name + " must be a valid calendar timestamp"); }
    }
}
