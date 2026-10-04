package info.lifeinuk.backend.guides;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GuideImportReaderTest {
    private final GuideImportReader reader = new GuideImportReader();
    static String fixture() throws Exception {
        try(var in=GuideImportReaderTest.class.getResourceAsStream("/guides/synthetic-import.json")) {
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    GuideImportDefinition parse(String value) { return reader.parse(value.getBytes(StandardCharsets.UTF_8)); }

    @Test
    void localFileRetainsExactMarkdownTextTimestampsAndSourceArrayOrder() throws Exception {
        var input=reader.read(Path.of(getClass().getResource("/guides/synthetic-import.json").toURI()));
        assertThat(input.slug()).isEqualTo("synthetic-guide");assertThat(input.status()).isEqualTo(GuideStatus.DRAFT);
        assertThat(input.publishedAt()).isNull();assertThat(input.updatedAt()).isEqualTo(Instant.parse("2026-10-04T12:00:00.123456Z"));
        assertThat(input.content()).isEqualTo("# Synthetic document\n\n  Markdown **text** — £, 示例!  \n");
        assertThat(input.sources()).extracting(GuideImportDefinition.Source::title).containsExactly("First editorial reference","Second editorial reference");
        assertThatThrownBy(()->input.sources().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings={"slug","category","title","summary","content","status","updatedAt","sources"})
    void rejectsMissingRequiredRootFields(String field) throws Exception {
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        var root=(tools.jackson.databind.node.ObjectNode)json.readTree(fixture());root.remove(field);
        assertThatIllegalArgumentException().isThrownBy(()->parse(json.writeValueAsString(root))).withMessageContaining(field);
    }

    @ParameterizedTest
    @ValueSource(strings={"organisation","title","url","accessedAt"})
    void rejectsIncompleteSourceBeforePersistence(String field) throws Exception {
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        var root=json.readTree(fixture());((tools.jackson.databind.node.ObjectNode)root.get("sources").get(0)).remove(field);
        assertThatIllegalArgumentException().isThrownBy(()->parse(json.writeValueAsString(root)))
                .withMessageContaining("sources[0]").withMessageContaining(field);
    }

    @ParameterizedTest
    @ValueSource(strings={"blank-slug","bad-category","blank-title","long-summary","wrong-content-type","bad-status",
            "published-without-time","draft-with-time","publication-after-update","wrong-source-type","blank-source-url",
            "invalid-source-time","timestamp-no-offset","timestamp-nanos","timestamp-invalid-date","timestamp-leap-second",
            "unknown-order-field","unknown-root-field","duplicate-keys","trailing-content","invalid-json"})
    void rejectsMalformedKnownFieldsPublicationAndAmbiguousDocuments(String fault) throws Exception {
        String value=fixture();
        value=switch(fault) {
            case "blank-slug" -> value.replace("\"synthetic-guide\"","\" \"");
            case "bad-category" -> value.replace("\"example-category\"","\"NOT VALID\"");
            case "blank-title" -> value.replace("Synthetic guide — 示例","   ");
            case "long-summary" -> value.replace("An offline importer fixture, not real guidance.","x".repeat(1001));
            case "wrong-content-type" -> {
                var root=(tools.jackson.databind.node.ObjectNode)tools.jackson.databind.json.JsonMapper.builder().build().readTree(value);
                root.put("content",10);yield root.toString();
            }
            case "bad-status" -> value.replace("\"DRAFT\"","\"REVIEWING\"");
            case "published-without-time" -> value.replace("\"DRAFT\"","\"PUBLISHED\"");
            case "draft-with-time" -> value.replace("\"publishedAt\": null","\"publishedAt\": \"2026-10-04T12:00:00Z\"");
            case "publication-after-update" -> value.replace("\"DRAFT\"","\"PUBLISHED\"")
                    .replace("\"publishedAt\": null","\"publishedAt\": \"2026-10-05T12:00:00Z\"");
            case "wrong-source-type" -> value.replace("\"organisation\": \"Offline organisation B\"","\"organisation\": true");
            case "blank-source-url" -> value.replace("https://example.invalid/reference-b"," ");
            case "invalid-source-time" -> value.replace("2026-10-01T12:00:00Z","tomorrow");
            case "timestamp-no-offset" -> value.replace("2026-10-04T12:00:00.123456Z","2026-10-04T12:00:00");
            case "timestamp-nanos" -> value.replace(".123456Z",".123456789Z");
            case "timestamp-invalid-date" -> value.replace("2026-10-04T12:00:00.123456Z","2026-02-30T12:00:00Z");
            case "timestamp-leap-second" -> value.replace("2026-10-04T12:00:00.123456Z","2026-10-04T12:00:60Z");
            case "unknown-order-field" -> value.replace("\"organisation\": \"Offline organisation B\"","\"sourceOrder\": -1, \"organisation\": \"Offline organisation B\"");
            case "unknown-root-field" -> value.replace("\"slug\":","\"unrecognised\":true,\"slug\":");
            case "duplicate-keys" -> value.replace("\"slug\":","\"slug\":\"other\",\"slug\":");
            case "trailing-content" -> value+"{}";
            default -> "{not JSON";
        };
        final String invalid=value;
        assertThatIllegalArgumentException().isThrownBy(()->parse(invalid));
    }

    @Test
    void commandWithoutLiteralCliOptionDoesNotReadAnyFileOrInvokePersistence() throws Exception {
        var reader=mock(GuideImportReader.class);var importer=mock(GuideImporter.class);
        var command=new GuideImportCommand(reader,importer);
        command.run(new DefaultApplicationArguments());
        command.run(new DefaultApplicationArguments("--spring.profiles.active=anything","--some-other-option=x"));
        verifyNoInteractions(reader,importer);
    }

    @ParameterizedTest
    @ValueSource(strings={"--import-guide","--import-guide=","--import-guide= "})
    void commandRejectsMissingPathWithoutDoingAnything(String arg) {
        var reader=mock(GuideImportReader.class);var importer=mock(GuideImporter.class);
        var command=new GuideImportCommand(reader,importer);
        assertThatIllegalArgumentException().isThrownBy(()->command.run(new DefaultApplicationArguments(arg)));
        verifyNoInteractions(reader,importer);
    }

    @Test
    void multipleFilesAndInvalidFileDoNotInvokeImporter() throws Exception {
        var reader=mock(GuideImportReader.class);var importer=mock(GuideImporter.class);
        var command=new GuideImportCommand(reader,importer);
        assertThatIllegalArgumentException().isThrownBy(()->command.run(new DefaultApplicationArguments("--import-guide=a","--import-guide=b")));
        verifyNoInteractions(reader,importer);
        when(reader.read(Path.of("invalid.json"))).thenThrow(new IllegalArgumentException("content must be nonblank"));
        assertThatIllegalArgumentException().isThrownBy(()->command.run(new DefaultApplicationArguments("--import-guide=invalid.json")));
        verifyNoInteractions(importer);
    }
}
