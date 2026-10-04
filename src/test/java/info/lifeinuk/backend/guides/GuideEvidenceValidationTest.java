package info.lifeinuk.backend.guides;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class GuideEvidenceValidationTest {
    final GuideImportReader reader = new GuideImportReader();
    final JsonMapper json = JsonMapper.builder().build();
    static String fixture() throws Exception {
        try (var in = GuideEvidenceValidationTest.class.getResourceAsStream("/guides/synthetic-evidence-import.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    GuideImportDefinition parse(String input) { return reader.parse(input.getBytes(StandardCharsets.UTF_8)); }

    @Test void validMultipleSourcesSharedSourcesAndIndependentOrdering() throws Exception {
        var input = parse(fixture());
        assertThat(input.sources()).extracting(GuideImportDefinition.Source::key).containsExactly("official-b", "official-a");
        assertThat(input.evidence()).extracting(GuideImportDefinition.Evidence::key).containsExactly("example-application", "example-condition");
        assertThat(input.evidence().getFirst().supports()).extracting(GuideImportDefinition.Support::sourceKey).containsExactly("official-a", "official-b");
        assertThat(input.evidence().get(1).supports().getFirst().sourceKey()).isEqualTo("official-a");
        assertThatThrownBy(() -> input.evidence().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> input.evidence().getFirst().supports().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @ParameterizedTest
    @ValueSource(strings={"duplicate-source", "blank-source-key", "bad-source-key", "duplicate-evidence", "blank-evidence-key",
        "blank-statement", "empty-supports", "missing-source", "blank-note", "no-locator-excerpt", "blank-locator",
        "blank-excerpt", "wrong-note-type", "wrong-evidence-type", "wrong-support-type", "unknown-evidence-field",
        "unknown-support-field", "unsafe-url", "credential-url", "relative-url", "wrong-key-type", "unused-evidence"})
    void rejectsInvalidWholeDefinitionsBeforePersistence(String fault) throws Exception {
        var root = (ObjectNode)json.readTree(fixture());
        var source = (ObjectNode)root.get("sources").get(0);
        var evidence = (ObjectNode)root.get("evidence").get(0);
        var support = (ObjectNode)evidence.get("supports").get(0);
        switch (fault) {
            case "duplicate-source" -> source.put("key", "official-a");
            case "blank-source-key" -> source.put("key", " ");
            case "bad-source-key" -> source.put("key", "Official_B");
            case "duplicate-evidence" -> evidence.put("key", "example-condition");
            case "blank-evidence-key" -> evidence.put("key", " ");
            case "blank-statement" -> evidence.put("statement", " ");
            case "empty-supports" -> evidence.putArray("supports");
            case "missing-source" -> support.put("sourceKey", "not-present");
            case "blank-note" -> support.put("note", " ");
            case "no-locator-excerpt" -> { support.remove("locator"); support.remove("excerpt"); }
            case "blank-locator" -> support.put("locator", " ");
            case "blank-excerpt" -> support.put("excerpt", " ");
            case "wrong-note-type" -> support.put("note", 10);
            case "wrong-evidence-type" -> root.putNull("evidence");
            case "wrong-support-type" -> evidence.put("supports", false);
            case "unknown-evidence-field" -> evidence.put("strength", "MUST");
            case "unknown-support-field" -> support.put("approved", true);
            case "unsafe-url" -> source.put("url", "javascript:alert(1)");
            case "credential-url" -> source.put("url", "https://user:secret@example.invalid/b");
            case "relative-url" -> source.put("url", "/b");
            case "wrong-key-type" -> source.put("key", 10);
            case "unused-evidence" -> root.put("content", "No references here.");
        }
        assertThatIllegalArgumentException().isThrownBy(() -> parse(json.writeValueAsString(root)));
    }
    @ParameterizedTest
    @ValueSource(strings={"[查看官方依据](#guide-evidence-example)",
        "[查看官方依据](#guide-evidence-example) [查看官方依据](#guide-evidence-example)",
        "[依据][ref]\n\n[ref]: #guide-evidence-example", "[依据](<#guide-evidence-example>)",
        "> [依据](#guide-evidence-example)", "- [依据](#guide-evidence-example)"})
    void actualCommonMarkLinksResolveIncludingRepeatedAndReferenceStyleLinks(String markdown) {
        assertThatCode(() -> GuideEvidenceReferences.validate(markdown, Set.of("example"))).doesNotThrowAnyException();
    }
    @ParameterizedTest
    @ValueSource(strings={"[依据](#guide-evidence-)", "[依据](#guide-evidence-BAD)",
        "[依据](#guide-evidence-unknown)", "[依据](#guide-evidence-example?query)", "[依据](#guide-evidence-example/other)"})
    void malformedAndUnknownReservedLinksFail(String markdown) {
        assertThatIllegalArgumentException().isThrownBy(() -> GuideEvidenceReferences.validate(markdown, Set.of("example")));
    }
    @ParameterizedTest
    @ValueSource(strings={"`[依据](#guide-evidence-unknown)`", "`` [依据](#guide-evidence-unknown) ``",
        "```markdown\n[依据](#guide-evidence-unknown)\n```", "~~~\n[依据](#guide-evidence-unknown)\n~~~",
        "    [依据](#guide-evidence-unknown)", "\\[依据](#guide-evidence-unknown)",
        "<!-- [依据](#guide-evidence-unknown) -->"})
    void codeAndLiteralExamplesAreNotLinks(String markdown) {
        assertThatCode(() -> GuideEvidenceReferences.validate(markdown, Set.of())).doesNotThrowAnyException();
        assertThatIllegalArgumentException().isThrownBy(() -> GuideEvidenceReferences.validate(markdown, Set.of("example")));
    }
    @Test void markerLookingTextDoesNotCountAsUsingEvidence() {
        assertThatIllegalArgumentException().isThrownBy(() -> GuideEvidenceReferences.validate("`[依据](#guide-evidence-example)`", Set.of("example")));
    }
    @Test void missingRequiredSupportNoteIsNotDefaulted() throws Exception {
        var root = json.readTree(fixture());
        ((ObjectNode)root.get("evidence").get(1).get("supports").get(0)).remove("note");
        assertThatIllegalArgumentException().isThrownBy(() -> parse(json.writeValueAsString(root))).withMessageContaining("note");
    }
}
