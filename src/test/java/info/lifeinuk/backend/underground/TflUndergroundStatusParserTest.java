package info.lifeinuk.backend.underground;

import info.lifeinuk.backend.evidence.EvidenceArtifact;
import info.lifeinuk.backend.evidence.IngestionRun;
import info.lifeinuk.backend.source.Source;
import info.lifeinuk.backend.source.SourceEndpoint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class TflUndergroundStatusParserTest {
    private final TflUndergroundStatusParser parser = new TflUndergroundStatusParser();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String STATUS = "{\"statusSeverity\":9,\"statusSeverityDescription\":\"  Delays — Café!  \"}";
    private static final String LINE = "{\"id\":\" z-line \",\"name\":\" Source £ name \",\"modeName\":\"tube\",\"lineStatuses\":[" + STATUS + "]}";

    private EvidenceArtifact artifact(byte[] bytes) {
        var endpoint = SourceEndpoint.tflUnderground(Source.tfl());
        endpoint.qualify("Offline owner decision", "Offline retention policy");
        var run = IngestionRun.start(endpoint, Instant.EPOCH);
        run.succeed(Instant.EPOCH.plusSeconds(2));
        return new EvidenceArtifact(run, bytes, "application/json; charset=utf-8", Instant.EPOCH.plusSeconds(1));
    }
    private EvidenceArtifact artifact(String text) { return artifact(text.getBytes(StandardCharsets.UTF_8)); }
    private UndergroundStatusInterpretation parse(String text) { return parser.parse(artifact(text)); }
    private byte[] fixture() throws IOException {
        try (var input = getClass().getResourceAsStream("/tfl/underground-status-response.json")) {
            return input.readAllBytes();
        }
    }

    @Test
    void representativeFixturePreservesFactsProvenanceAndCompleteEvidence() throws IOException {
        var evidence = artifact(fixture());
        var run = evidence.getIngestionRun();
        var endpoint = run.getSourceEndpoint();
        var source = endpoint.getSource();
        var bytes = evidence.getPayload();
        var hash = evidence.getSha256();
        var result = parser.parse(evidence);
        assertThat(result.evidenceArtifactId()).isEqualTo(evidence.getId());
        assertThat(result.observedAt()).isEqualTo(evidence.getObservedAt());
        assertThat(result.lines()).containsExactly(new UndergroundLineStatus("fixture-line", "Offline Underground fixture",
                List.of(new UndergroundOperationalStatus(9, "Minor Delays", Optional.of("  Test only: delays — Café, £5; punctuation!  ")))));
        assertThat(parser.parse(evidence)).isEqualTo(result);
        assertThat(evidence.getPayload()).containsExactly(bytes);
        assertThat(evidence.getSha256()).isEqualTo(hash);
        assertThat(evidence.getByteSize()).isEqualTo(bytes.length);
        assertThat(evidence.getMediaType()).isEqualTo("application/json; charset=utf-8");
        assertThat(evidence.getObservedAt()).isEqualTo(Instant.EPOCH.plusSeconds(1));
        assertThat(evidence.getIngestionRun()).isSameAs(run);
        assertThat(run.getSourceEndpoint()).isSameAs(endpoint);
        assertThat(endpoint.getSource()).isSameAs(source);
        assertThat(run.getStatus()).isEqualTo(info.lifeinuk.backend.evidence.RunStatus.SUCCESS);
        assertThat(endpoint.isQualifiedAndEnabled()).isTrue();
    }

    @Test
    void multipleAndIdenticalStatusesAndAllLinesKeepSourceOrderAndText() {
        String first = LINE.replace(STATUS, STATUS + "," + STATUS.replace("9", "10") + "," + STATUS);
        String second = LINE.replace(" z-line ", "a-line");
        var result = parse("[" + first + "," + second + "]");
        assertThat(result.lines()).extracting(UndergroundLineStatus::lineId).containsExactly(" z-line ", "a-line");
        assertThat(result.lines().getFirst().lineName()).isEqualTo(" Source £ name ");
        assertThat(result.lines().getFirst().statuses()).extracting(UndergroundOperationalStatus::severity).containsExactly(9, 10, 9);
        assertThat(result.lines().getFirst().statuses()).extracting(UndergroundOperationalStatus::description)
                .containsExactly("  Delays — Café!  ", "  Delays — Café!  ", "  Delays — Café!  ");
        assertThat(result.lines().getFirst().statuses().getFirst().reason()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"\"", "\"  Café £ — punctuation!  \""})
    void optionalReasonPreservesPresenceAndExactText(String value) {
        var status = parse("[" + LINE.replace(STATUS, STATUS.replace("}", ",\"reason\":" + value + "}")) + "]")
                .lines().getFirst().statuses().getFirst();
        assertThat(status.reason()).isEqualTo(value.equals("null") ? Optional.empty() : Optional.of(JSON.readTree(value).stringValue()));
    }

    @Test
    void unrelatedMetadataDoesNotAffectNormalizedOutput() {
        var original = artifact("[" + LINE + "]");
        ObjectNode line = (ObjectNode) JSON.readTree(original.getPayload()).get(0);
        line.set("$type", JSON.readTree("{\"provider\":[1,true,null]}"));
        line.set("disruptions", JSON.readTree("[{}]"));
        line.put("created", "irrelevant metadata");
        ((ObjectNode) line.get("lineStatuses").get(0)).set("futureField", JSON.readTree("[1,{}]"));
        var extended = parser.parse(artifact("[" + JSON.writeValueAsString(line) + "]"));
        assertThat(extended.lines()).isEqualTo(parser.parse(original).lines());
    }

    @Test
    void emptyArraysRemainEmptyWithoutFabricatedStatus() {
        assertThat(parse("[]").lines()).isEmpty();
        assertThat(parse("[" + LINE.replace(STATUS, "") + "]").lines().getFirst().statuses()).isEmpty();
    }

    @Test
    void resultCollectionsAreImmutableAndDefensivelyCopied() {
        var statuses = new ArrayList<>(List.of(new UndergroundOperationalStatus(9, "Delays", Optional.empty())));
        var line = new UndergroundLineStatus("id", "name", statuses);
        var lines = new ArrayList<>(List.of(line));
        var result = new UndergroundStatusInterpretation(java.util.UUID.randomUUID(), Instant.EPOCH, lines);
        statuses.clear(); lines.clear();
        assertThat(result.lines()).hasSize(1);
        assertThat(line.statuses()).hasSize(1);
        assertThatThrownBy(() -> result.lines().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> line.statuses().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void historicalArtifactsAreIndependentAndUseNoLazyAssociations() {
        var old = artifact("[" + LINE + "]");
        var later = artifact("[" + LINE.replace(" z-line ", "later-line") + "]");
        old.getIngestionRun().getSourceEndpoint().setEnabled(false);
        assertThat(parser.parse(old).lines().getFirst().lineId()).isEqualTo(" z-line ");
        assertThat(parser.parse(later).lines().getFirst().lineId()).isEqualTo("later-line");
        assertThat(parser.parse(old).evidenceArtifactId()).isEqualTo(old.getId());
    }

    static Stream<String> malformedKnownFields() {
        var inputs = new ArrayList<String>();
        for (String field : List.of("id", "name", "modeName", "lineStatuses")) {
            for (String value : List.of("MISSING", "null", "true", "10", "{}")) {
                ObjectNode line = (ObjectNode) JSON.readTree(LINE);
                if (value.equals("MISSING")) { line.remove(field); } else { line.set(field, JSON.readTree(value)); }
                inputs.add("[" + JSON.writeValueAsString(line) + "]");
            }
        }
        for (String field : List.of("id", "name", "modeName")) {
            ObjectNode line = (ObjectNode) JSON.readTree(LINE); line.put(field, " \u2003 ");
            inputs.add("[" + JSON.writeValueAsString(line) + "]");
        }
        for (String field : List.of("statusSeverity", "statusSeverityDescription")) {
            for (String value : List.of("MISSING", "null", "true", "{}", "[]")) {
                ObjectNode line = (ObjectNode) JSON.readTree(LINE);
                ObjectNode status = (ObjectNode) line.get("lineStatuses").get(0);
                if (value.equals("MISSING")) { status.remove(field); } else { status.set(field, JSON.readTree(value)); }
                inputs.add("[" + JSON.writeValueAsString(line) + "]");
            }
        }
        for (String value : List.of("\"9\"", "9.0", "2147483648", "-2147483649")) {
            inputs.add("[" + LINE.replace("\"statusSeverity\":9", "\"statusSeverity\":" + value) + "]");
        }
        for (String value : List.of("10", "\" \u2003 \"")) {
            inputs.add("[" + LINE.replace("\"  Delays — Café!  \"", value) + "]");
        }
        for (String value : List.of("10", "true", "[]", "{}")) {
            inputs.add("[" + LINE.replace(STATUS, STATUS.replace("}", ",\"reason\":" + value + "}")) + "]");
        }
        return inputs.stream();
    }

    @ParameterizedTest
    @MethodSource("malformedKnownFields")
    void malformedRequiredFieldsAndReasonsFailWithoutCoercion(String input) {
        assertThatIllegalArgumentException().isThrownBy(() -> parse(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{}", "null", "true", "[null]", "[10]", "[[]]", "[] {}", "[] []", "[] garbage"})
    void malformedRootEntriesAndTrailingDocumentsFail(String input) {
        assertThatIllegalArgumentException().isThrownBy(() -> parse(input));
    }

    @Test
    void duplicateLineIdsAndWrongModesFail() {
        assertThatIllegalArgumentException().isThrownBy(() -> parse("[" + LINE + "," + LINE + "]"));
        assertThatIllegalArgumentException().isThrownBy(() -> parse("[" + LINE.replace("tube", "dlr") + "]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "10", "[]", "true"})
    void malformedStatusElementsFail(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> parse("[" + LINE.replace(STATUS, value) + "]"));
    }

    @Test
    void duplicateJsonKeysAreRejectedAtLineStatusAndUnknownMetadataLevels() {
        for (String input : List.of(
                "[" + LINE.replace("\"id\":", "\"id\":\"other\",\"id\":") + "]",
                "[" + LINE.replace("\"statusSeverity\":", "\"statusSeverity\":10,\"statusSeverity\":") + "]",
                "[" + LINE.replace("\"name\":", "\"metadata\":{\"x\":1,\"x\":2},\"name\":") + "]")) {
            assertThatIllegalArgumentException().isThrownBy(() -> parse(input));
        }
    }
}
