package info.lifeinuk.backend.guides;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

/** guide-content-v1 digests of synthetic import definitions only. No Spring, database or network. */
class GuideContentDigestTest {
    // Fixed expectations, computed independently from the documented canonical form (not from this class).
    static final String SYNTHETIC_DIGEST = "acef08a6ffb317d591346985e7601174c3eb9f6544b1b8fc60be81d98821a0ec";
    /** synthetic-import.json with an explicit {@code "evidence": []}. */
    static final String SYNTHETIC_EMPTY_EVIDENCE_DIGEST = "2fd1675960ad24c98067d05d2dd0f63e05934f649509e4750a6f647e548c7696";
    static final String SYNTHETIC_EVIDENCE_DIGEST = "c3478d0200042c3b995d53585c40557be9b7f5b53f39d2231593991f73cd14d2";

    private final GuideImportReader reader = new GuideImportReader();
    private final JsonMapper json = JsonMapper.builder().build();

    static String fixture(String name) throws Exception {
        try (var in = GuideContentDigestTest.class.getResourceAsStream("/guides/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    GuideImportDefinition parse(String value) { return reader.parse(value.getBytes(StandardCharsets.UTF_8)); }
    String digest(String value) { return GuideContentDigest.digest(parse(value)); }
    String evidenceFixture() throws Exception { return fixture("synthetic-evidence-import.json"); }

    /** The evidence fixture after one edit to its JSON tree. */
    String edited(Consumer<ObjectNode> edit) throws Exception {
        var root = (ObjectNode) json.readTree(evidenceFixture());
        edit.accept(root);
        return json.writeValueAsString(root);
    }
    static ObjectNode source(ObjectNode root, int index) { return (ObjectNode) root.get("sources").get(index); }
    static ObjectNode evidence(ObjectNode root, int index) { return (ObjectNode) root.get("evidence").get(index); }
    static ObjectNode support(ObjectNode root, int evidence, int support) {
        return (ObjectNode) root.get("evidence").get(evidence).get("supports").get(support);
    }
    static void swap(JsonNode array, int first, int second) {
        var items = (ArrayNode) array;
        var held = items.get(first);
        items.set(first, items.get(second));
        items.set(second, held);
    }

    @Test
    void syntheticFixturesHaveStableDigests() throws Exception {
        assertThat(digest(fixture("synthetic-import.json"))).isEqualTo(SYNTHETIC_DIGEST);
        assertThat(digest(evidenceFixture())).isEqualTo(SYNTHETIC_EVIDENCE_DIGEST).matches("[0-9a-f]{64}");
        assertThat(GuideContentDigest.VERSION).isEqualTo("guide-content-v1");
    }

    @Test
    void canonicalFormIsTheDocumentedSingleLine() {
        var guide = new GuideImportDefinition("tiny-guide", "example-category", "T", "S", "# T\n\nText.[查看官方依据](#guide-evidence-claim)\n",
                GuideStatus.PUBLISHED, Instant.parse("2026-10-04T12:00:00Z"), Instant.parse("2026-10-04T12:00:00.5Z"),
                List.of(new GuideImportDefinition.Source("official", "Org", "Ref", "https://example.invalid/r", Instant.parse("2026-10-01T00:00:00Z")),
                        new GuideImportDefinition.Source("Legacy org", "Legacy ref", "https://example.invalid/l", Instant.parse("2026-10-02T00:00:00Z"))),
                List.of(new GuideImportDefinition.Evidence("claim", "Statement.",
                        List.of(new GuideImportDefinition.Support("official", "Section 1", null, "Note.")))));
        assertThat(GuideContentDigest.canonicalForm(guide)).isEqualTo("{\"canonicalization\":\"guide-content-v1\",\"slug\":\"tiny-guide\","
                + "\"category\":\"example-category\",\"title\":\"T\",\"summary\":\"S\","
                + "\"content\":\"# T\\u000a\\u000aText.[查看官方依据](#guide-evidence-claim)\\u000a\",\"status\":\"PUBLISHED\","
                + "\"publishedAt\":\"2026-10-04T12:00:00.000000Z\",\"updatedAt\":\"2026-10-04T12:00:00.500000Z\","
                + "\"sources\":[{\"key\":\"official\",\"organisation\":\"Org\",\"title\":\"Ref\",\"url\":\"https://example.invalid/r\","
                + "\"accessedAt\":\"2026-10-01T00:00:00.000000Z\"},{\"key\":null,\"organisation\":\"Legacy org\",\"title\":\"Legacy ref\","
                + "\"url\":\"https://example.invalid/l\",\"accessedAt\":\"2026-10-02T00:00:00.000000Z\"}],"
                + "\"evidence\":[{\"key\":\"claim\",\"statement\":\"Statement.\",\"supports\":[{\"sourceKey\":\"official\","
                + "\"locator\":\"Section 1\",\"excerpt\":null,\"note\":\"Note.\"}]}]}");
    }

    @Test
    void repeatedDigestsAreIdenticalAcrossReadersLocalesAndTimeZones() throws Exception {
        String expected = digest(evidenceFixture());
        Locale locale = Locale.getDefault();
        TimeZone zone = TimeZone.getDefault();
        try {
            for (var setting : List.of(Locale.forLanguageTag("tr-TR"), Locale.forLanguageTag("ar-SA-u-nu-arab"), Locale.CHINA)) {
                Locale.setDefault(setting);
                TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
                assertThat(GuideContentDigest.digest(new GuideImportReader().parse(evidenceFixture().getBytes(StandardCharsets.UTF_8))))
                        .isEqualTo(expected);
            }
        } finally {
            Locale.setDefault(locale);
            TimeZone.setDefault(zone);
        }
        assertThat(digest(evidenceFixture())).isEqualTo(expected).isEqualTo(SYNTHETIC_EVIDENCE_DIGEST);
    }

    /** The same tree with every object's properties in reverse order. */
    JsonNode reversed(JsonNode node) {
        if (node.isObject()) {
            var copy = json.createObjectNode();
            var properties = new ArrayList<>(node.properties());
            for (int index = properties.size() - 1; index >= 0; index--) {
                copy.set(properties.get(index).getKey(), reversed(properties.get(index).getValue()));
            }
            return copy;
        }
        if (node.isArray()) {
            var copy = json.createArrayNode();
            node.forEach(item -> copy.add(reversed(item)));
            return copy;
        }
        return node;
    }

    @Test
    void jsonFormattingAndPropertyOrderDoNotChangeTheDigest() throws Exception {
        String compactReversed = json.writeValueAsString(reversed(json.readTree(evidenceFixture())));
        assertThat(compactReversed).doesNotContain("\n  ").startsWith("{\"evidence\"");
        assertThat(digest(compactReversed)).isEqualTo(SYNTHETIC_EVIDENCE_DIGEST);
        String spaced = evidenceFixture().replace("\":", "\" :\t").replace(",\n", " ,\r\n");
        assertThat(spaced).isNotEqualTo(evidenceFixture());
        assertThat(digest(spaced)).isEqualTo(SYNTHETIC_EVIDENCE_DIGEST);
    }

    @Test
    void equivalentTimestampOffsetsAndPrecisionDoNotChangeTheDigest() throws Exception {
        assertThat(digest(edited(root -> {
            root.put("updatedAt", "2026-10-04T13:00:00+01:00");
            source(root, 0).put("accessedAt", "2026-10-04T05:00:00.000000-05:00");
            source(root, 1).put("accessedAt", "2026-10-04T10:00:00.0Z");
        }))).isEqualTo(SYNTHETIC_EVIDENCE_DIGEST);
        String published = edited(root -> { root.put("status", "PUBLISHED"); root.put("publishedAt", "2026-10-04T12:00:00Z"); });
        String offset = edited(root -> { root.put("status", "PUBLISHED"); root.put("publishedAt", "2026-10-04T20:00:00+08:00"); });
        assertThat(digest(offset)).isEqualTo(digest(published));
    }

    static Stream<Arguments> editorialChanges() {
        return Stream.of(
                Arguments.of("slug", (Consumer<ObjectNode>) root -> root.put("slug", "synthetic-evidence-guide-2")),
                Arguments.of("category", (Consumer<ObjectNode>) root -> root.put("category", "other-category")),
                Arguments.of("title", (Consumer<ObjectNode>) root -> root.put("title", "Synthetic evidence guide.")),
                Arguments.of("summary", (Consumer<ObjectNode>) root -> root.put("summary", "Offline provenance example only")),
                Arguments.of("content", (Consumer<ObjectNode>) root -> root.put("content", root.get("content").stringValue() + "\n")),
                Arguments.of("status", (Consumer<ObjectNode>) root -> { root.put("status", "PUBLISHED"); root.put("publishedAt", "2026-10-04T12:00:00Z"); }),
                Arguments.of("updatedAt", (Consumer<ObjectNode>) root -> root.put("updatedAt", "2026-10-04T12:00:00.000001Z")),
                Arguments.of("source.key", (Consumer<ObjectNode>) root -> {
                    source(root, 1).put("key", "official-c");
                    support(root, 0, 0).put("sourceKey", "official-c");
                    support(root, 1, 0).put("sourceKey", "official-c");
                }),
                Arguments.of("source.organisation", (Consumer<ObjectNode>) root -> source(root, 0).put("organisation", "Example B2")),
                Arguments.of("source.title", (Consumer<ObjectNode>) root -> source(root, 0).put("title", "Reference B2")),
                Arguments.of("source.url", (Consumer<ObjectNode>) root -> source(root, 0).put("url", "https://example.invalid/B")),
                Arguments.of("source.accessedAt", (Consumer<ObjectNode>) root -> source(root, 0).put("accessedAt", "2026-10-04T10:00:01Z")),
                Arguments.of("evidence.statement", (Consumer<ObjectNode>) root -> evidence(root, 0).put("statement", "人工审核的示例主张")),
                Arguments.of("support.sourceKey", (Consumer<ObjectNode>) root -> support(root, 1, 0).put("sourceKey", "official-b")),
                Arguments.of("support.locator", (Consumer<ObjectNode>) root -> support(root, 0, 0).put("locator", "Application section 2")),
                Arguments.of("support.locator added", (Consumer<ObjectNode>) root -> support(root, 0, 1).put("locator", "Section B")),
                Arguments.of("support.excerpt", (Consumer<ObjectNode>) root -> support(root, 0, 1).put("excerpt", "Synthetic quotation.")),
                Arguments.of("support.excerpt added", (Consumer<ObjectNode>) root -> support(root, 0, 0).put("excerpt", "Added quotation.")),
                Arguments.of("support.note", (Consumer<ObjectNode>) root -> support(root, 1, 0).put("note", "Preserve the source qualification")),
                Arguments.of("support removed", (Consumer<ObjectNode>) root -> ((ArrayNode) evidence(root, 0).get("supports")).remove(1)),
                Arguments.of("source added", (Consumer<ObjectNode>) root -> ((ArrayNode) root.get("sources")).addObject()
                        .put("key", "official-c").put("organisation", "Example C").put("title", "Reference C")
                        .put("url", "https://example.invalid/c").put("accessedAt", "2026-10-04T10:00:00Z")),
                Arguments.of("source order", (Consumer<ObjectNode>) root -> swap(root.get("sources"), 0, 1)),
                Arguments.of("evidence order", (Consumer<ObjectNode>) root -> swap(root.get("evidence"), 0, 1)),
                Arguments.of("support order", (Consumer<ObjectNode>) root -> swap(evidence(root, 0).get("supports"), 0, 1)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("editorialChanges")
    void everyEditorialChangeChangesTheDigest(String label, Consumer<ObjectNode> edit) throws Exception {
        String changed = edited(edit);
        assertThat(digest(changed)).as(label).isNotEqualTo(SYNTHETIC_EVIDENCE_DIGEST).matches("[0-9a-f]{64}");
    }

    @Test
    void absentAndNullOptionalFieldsAreTheSame() throws Exception {
        assertThat(digest(edited(root -> {
            root.remove("publishedAt");
            support(root, 0, 0).remove("excerpt");
            support(root, 0, 1).putNull("locator");
            support(root, 1, 0).putNull("excerpt");
        }))).isEqualTo(SYNTHETIC_EVIDENCE_DIGEST);
        // Legacy unkeyed sources: an absent and an explicit null key are equivalent.
        var legacy = (ObjectNode) json.readTree(fixture("synthetic-import.json"));
        ((ObjectNode) legacy.get("sources").get(0)).putNull("key");
        assertThat(digest(json.writeValueAsString(legacy))).isEqualTo(SYNTHETIC_DIGEST);
    }

    @Test
    void absentEvidenceAndExplicitEmptyEvidenceHaveDifferentDigests() throws Exception {
        // The importer treats them differently for a Guide that already has evidence, so the digest does too.
        var absent = parse(fixture("synthetic-import.json"));
        assertThat(absent.evidence()).isNull();
        assertThat(GuideContentDigest.canonicalForm(absent)).endsWith(",\"evidence\":null}");
        assertThat(GuideContentDigest.digest(absent)).isEqualTo(SYNTHETIC_DIGEST);

        var root = (ObjectNode) json.readTree(fixture("synthetic-import.json"));
        root.putArray("evidence");
        var empty = parse(json.writeValueAsString(root));
        assertThat(empty.evidence()).isEmpty();
        assertThat(GuideContentDigest.canonicalForm(empty)).endsWith(",\"evidence\":[]}");
        assertThat(GuideContentDigest.digest(empty)).isEqualTo(SYNTHETIC_EMPTY_EVIDENCE_DIGEST).isNotEqualTo(SYNTHETIC_DIGEST);

        // Explicit JSON null is not a third state: the reader rejects it.
        root.putNull("evidence");
        assertThatIllegalArgumentException().isThrownBy(() -> parse(json.writeValueAsString(root)))
                .withMessageContaining("evidence must be an array");
    }

    @Test
    void stringsAreHashedExactlyWithoutNormalization() {
        String composed = titleDigest("\u00e9");
        assertThat(titleDigest("e\u0301")).isNotEqualTo(composed);
        assertThat(titleDigest("\u00e9 ")).isNotEqualTo(composed);
        assertThat(titleDigest("\uD800")).isNotEqualTo(titleDigest("\uFFFD")).isNotEqualTo(titleDigest("?"));
        assertThat(canonicalTitle("a\"b\\c\td\u001f\uD83D\uDE00\uDC00")).isEqualTo("\"title\":\"a\\\"b\\\\c\\u0009d\\u001f\uD83D\uDE00\\udc00\"");
    }

    static GuideImportDefinition titled(String title) {
        return new GuideImportDefinition("tiny-guide", "example-category", title, "S", "Body", GuideStatus.DRAFT, null,
                Instant.parse("2026-10-04T12:00:00Z"), List.of());
    }
    static String titleDigest(String title) { return GuideContentDigest.digest(titled(title)); }
    static String canonicalTitle(String title) {
        String form = GuideContentDigest.canonicalForm(titled(title));
        return form.substring(form.indexOf("\"title\""), form.indexOf(",\"summary\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"slug\":\"x\"}",
        "not json",
        "{\"slug\":\"Bad Slug\",\"category\":\"c\",\"title\":\"t\",\"summary\":\"s\",\"content\":\"c\",\"status\":\"DRAFT\",\"updatedAt\":\"2026-10-04T12:00:00Z\",\"sources\":[]}",
        "{\"slug\":\"s\",\"category\":\"c\",\"title\":\" \",\"summary\":\"s\",\"content\":\"c\",\"status\":\"DRAFT\",\"updatedAt\":\"2026-10-04T12:00:00Z\",\"sources\":[]}",
        "{\"slug\":\"s\",\"category\":\"c\",\"title\":\"t\",\"summary\":\"s\",\"content\":\"c\",\"status\":\"DRAFT\",\"updatedAt\":\"2026-10-04T12:00:00.1234567Z\",\"sources\":[]}",
        "{\"slug\":\"s\",\"category\":\"c\",\"title\":\"t\",\"summary\":\"s\",\"content\":\"c\",\"status\":\"DRAFT\",\"updatedAt\":\"2026-10-04T12:00:00\",\"sources\":[]}",
        "{\"slug\":\"s\",\"category\":\"c\",\"title\":\"t\",\"summary\":\"s\",\"content\":\"c\",\"status\":\"PUBLISHED\",\"updatedAt\":\"2026-10-04T12:00:00Z\",\"sources\":[]}",
        "{\"slug\":\"s\",\"category\":\"c\",\"title\":\"t\",\"summary\":\"s\",\"content\":\"c\",\"status\":\"DRAFT\",\"updatedAt\":\"2026-10-04T12:00:00Z\",\"sources\":[],\"extra\":1}",
        "{\"slug\":\"s\",\"slug\":\"s\",\"category\":\"c\",\"title\":\"t\",\"summary\":\"s\",\"content\":\"c\",\"status\":\"DRAFT\",\"updatedAt\":\"2026-10-04T12:00:00Z\",\"sources\":[]}"
    })
    void invalidInputIsRejectedBeforeAnyDigestExists(String input) {
        assertThatIllegalArgumentException().isThrownBy(() -> digest(input));
    }

    @Test
    void invalidReferencesAreRejectedBeforeAnyDigestExists() {
        assertThatIllegalArgumentException().isThrownBy(() -> digest(edited(root -> support(root, 0, 0).put("sourceKey", "missing"))));
        assertThatIllegalArgumentException().isThrownBy(() -> digest(edited(root -> ((ArrayNode) root.get("evidence")).remove(1))))
                .withMessageContaining("example-condition");
        assertThatIllegalArgumentException().isThrownBy(() -> digest(edited(root -> {
            var duplicate = evidence(root, 1).deepCopy();
            ((ArrayNode) root.get("evidence")).add(duplicate);
        })));
    }
}
