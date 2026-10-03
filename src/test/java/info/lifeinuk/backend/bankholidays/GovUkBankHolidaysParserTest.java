package info.lifeinuk.backend.bankholidays;

import info.lifeinuk.backend.evidence.EvidenceArtifact;
import info.lifeinuk.backend.evidence.IngestionRun;
import info.lifeinuk.backend.source.Source;
import info.lifeinuk.backend.source.SourceEndpoint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class GovUkBankHolidaysParserTest {
    private final GovUkBankHolidaysParser parser = new GovUkBankHolidaysParser();
    private final JsonMapper json = JsonMapper.builder().build();

    private byte[] fixture() {
        try (var stream = getClass().getResourceAsStream("/bankholidays/representative.json")) {
            return stream.readAllBytes();
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }

    private EvidenceArtifact artifact(byte[] payload) {
        SourceEndpoint endpoint = SourceEndpoint.bankHolidays(new Source(Source.BANK_HOLIDAYS_KEY, "GOV.UK Bank Holidays"), Instant.EPOCH);
        endpoint.qualify("Offline fixture owner approval", "Offline fixture retention");
        IngestionRun run = IngestionRun.start(endpoint, Instant.EPOCH);
        run.succeed(Instant.EPOCH.plusSeconds(2));
        return new EvidenceArtifact(run, payload, "application/json; charset=utf-8", Instant.EPOCH.plusSeconds(1));
    }

    private ObjectNode root() { return (ObjectNode) json.readTree(fixture()); }
    private ObjectNode event(ObjectNode root) { return (ObjectNode) root.get("england-and-wales").get("events").get(0); }
    private void rejected(JsonNode input) { rejected(json.writeValueAsBytes(input)); }
    private void rejected(byte[] input) {
        assertThatIllegalArgumentException().isThrownBy(() -> parser.parse(artifact(input)));
    }

    @Test
    void allDivisionsProduceExactTypedSourceFactsAndProvenance() {
        EvidenceArtifact evidence = artifact(fixture());
        BankHolidaysInterpretation result = parser.parse(evidence);
        assertThat(result.evidenceArtifactId()).isEqualTo(evidence.getId());
        assertThat(result.facts()).containsExactly(
                new BankHolidayFact(BankHolidayDivision.ENGLAND_AND_WALES, " New Year’s Day ", LocalDate.of(2026, 1, 1), "", true),
                new BankHolidayFact(BankHolidayDivision.ENGLAND_AND_WALES, "Spring bank holiday", LocalDate.of(2026, 5, 25), "  Source note £  ", false),
                new BankHolidayFact(BankHolidayDivision.SCOTLAND, "2nd January", LocalDate.of(2026, 1, 2), "Substitute day", true),
                new BankHolidayFact(BankHolidayDivision.NORTHERN_IRELAND, "St Patrick’s Day", LocalDate.of(2026, 3, 17), "", false));
        assertThatThrownBy(() -> result.facts().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void parsingIsRepeatableAndLeavesCompleteEvidenceAndAcquisitionStateUntouched() {
        EvidenceArtifact evidence = artifact(fixture());
        byte[] bytes = evidence.getPayload();
        String digest = evidence.getSha256();
        var run = evidence.getIngestionRun();
        var endpoint = run.getSourceEndpoint();
        var expected = parser.parse(evidence);
        assertThat(parser.parse(evidence)).isEqualTo(expected);
        assertThat(evidence.getPayload()).containsExactly(bytes);
        assertThat(evidence.getSha256()).isEqualTo(digest);
        assertThat(evidence.getMediaType()).isEqualTo("application/json; charset=utf-8");
        assertThat(evidence.getObservedAt()).isEqualTo(Instant.EPOCH.plusSeconds(1));
        assertThat(evidence.getIngestionRun()).isSameAs(run);
        assertThat(run.getSourceEndpoint()).isSameAs(endpoint);
        assertThat(run.getStartedAt()).isEqualTo(Instant.EPOCH);
        assertThat(run.getCompletedAt()).isEqualTo(Instant.EPOCH.plusSeconds(2));
        assertThat(run.getStatus()).isEqualTo(info.lifeinuk.backend.evidence.RunStatus.SUCCESS);
        assertThat(run.getFailureCode()).isNull();
        assertThat(run.getFailureMessage()).isNull();
        assertThat(endpoint.isQualifiedAndEnabled()).isTrue();
    }

    @Test
    void specificHistoricalArtifactsRemainIndependentWithoutAnyLookupOrNetworkDependency() {
        EvidenceArtifact old = artifact(fixture());
        EvidenceArtifact later = artifact(new String(fixture(), StandardCharsets.UTF_8).replace("2026", "2027").getBytes(StandardCharsets.UTF_8));
        var oldResult = parser.parse(old);
        var laterResult = parser.parse(later);
        assertThat(oldResult.evidenceArtifactId()).isEqualTo(old.getId()).isNotEqualTo(laterResult.evidenceArtifactId());
        assertThat(oldResult.facts().getFirst().date()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(laterResult.facts().getFirst().date()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(parser.parse(old)).isEqualTo(oldResult);
        assertThat(Arrays.stream(GovUkBankHolidaysParser.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers())).toList())
                .singleElement().satisfies(method -> assertThat(method.getParameterTypes()).containsExactly(EvidenceArtifact.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", " ", "null", "[]", "true", "\"text\"", "{}"})
    void invalidJsonAndMissingRootFail(String input) { rejected(input.getBytes(StandardCharsets.UTF_8)); }

    @ParameterizedTest
    @EnumSource(BankHolidayDivision.class)
    void everySupportedDivisionIsRequired(BankHolidayDivision division) {
        ObjectNode root = root();
        root.remove(division.sourceIdentifier());
        rejected(root);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "\"group\"", "42", "true", "{}"})
    void malformedDivisionObjectsFail(String input) {
        ObjectNode root = root();
        root.set("scotland", json.readTree(input));
        rejected(root);
    }

    @ParameterizedTest
    @ValueSource(strings = {"division", "events"})
    void requiredDivisionFieldsCannotBeAbsent(String field) {
        ObjectNode root = root();
        ((ObjectNode) root.get("scotland")).remove(field);
        rejected(root);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "\"events\"", "42", "false"})
    void malformedEventsFail(String input) {
        ObjectNode root = root();
        ((ObjectNode) root.get("scotland")).set("events", json.readTree(input));
        rejected(root);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[null]", "[[]]", "[\"event\"]", "[{}]"})
    void malformedEventObjectsFail(String input) {
        ObjectNode root = root();
        ((ObjectNode) root.get("scotland")).set("events", json.readTree(input));
        rejected(root);
    }

    @ParameterizedTest
    @ValueSource(strings = {"title", "date", "notes", "bunting"})
    void everyEventFieldIsRequired(String field) {
        ObjectNode root = root();
        event(root).remove(field);
        rejected(root);
    }

    @ParameterizedTest
    @ValueSource(strings = {"title", "date", "notes", "bunting"})
    void explicitNullIsNeverAFactualDefault(String field) {
        ObjectNode root = root();
        event(root).putNull(field);
        rejected(root);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-02-29", "2026-02-30", "2026-13-01", "2026-01-00", "26-01-01", "2026-1-01", "2026-01-01T00:00:00Z", " 2026-01-01", ""})
    void invalidDatesFail(String value) {
        ObjectNode root = root();
        event(root).put("date", value);
        rejected(root);
    }

    @ParameterizedTest
    @ValueSource(strings = {"title", "date", "notes"})
    void textFieldsCannotBeCoercedFromNumbersBooleansOrContainers(String field) {
        for (String value : new String[]{"1", "true", "[]", "{}"}) {
            ObjectNode root = root();
            event(root).set(field, json.readTree(value));
            rejected(root);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"false\"", "0", "1", "[]", "{}"})
    void buntingMustBeAnActualBoolean(String value) {
        ObjectNode root = root();
        event(root).set("bunting", json.readTree(value));
        rejected(root);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void emptyOrBlankTitlesAreNotFacts(String value) {
        ObjectNode root = root();
        event(root).put("title", value);
        rejected(root);
    }

    @Test
    void unknownRootDivisionFailsRatherThanBeingMappedOrIgnored() {
        ObjectNode root = root();
        root.set("unknown-region", root.get("scotland"));
        rejected(root);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"unknown-region\"", "\"northern-ireland\"", "null", "1", "true"})
    void nestedDivisionIdentifierMustMatchItsRootKey(String value) {
        ObjectNode root = root();
        ((ObjectNode) root.get("scotland")).set("division", json.readTree(value));
        rejected(root);
    }

    @Test
    void unexpectedDivisionAndEventFieldsFailExplicitly() {
        ObjectNode root = root();
        ((ObjectNode) root.get("scotland")).put("unexpected", "value");
        rejected(root);
        root = root();
        event(root).put("unexpected", "value");
        rejected(root);
    }

    @Test
    void duplicateKeysAndTrailingDocumentsFail() {
        String valid = new String(fixture(), StandardCharsets.UTF_8);
        rejected(valid.replace("\"bunting\": true", "\"bunting\": false, \"bunting\": true").getBytes(StandardCharsets.UTF_8));
        rejected((valid + " {}").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void emptyEventsAndLeapDatesAreValidAndOrderAndDuplicatesAreRetained() {
        ObjectNode root = root();
        ((ObjectNode) root.get("scotland")).set("events", json.createArrayNode());
        ((ObjectNode) root.get("northern-ireland")).set("events", json.createArrayNode());
        event(root).put("date", "2024-02-29");
        var events = (tools.jackson.databind.node.ArrayNode) root.get("england-and-wales").get("events");
        events.add(events.get(0).deepCopy());
        var facts = parser.parse(artifact(json.writeValueAsBytes(root))).facts();
        assertThat(facts).hasSize(3);
        assertThat(facts.get(0).date()).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(facts.get(1).title()).isEqualTo("Spring bank holiday");
        assertThat(facts.get(2)).isEqualTo(facts.get(0));
        for (BankHolidayDivision division : BankHolidayDivision.values()) {
            ((ObjectNode) root.get(division.sourceIdentifier())).set("events", json.createArrayNode());
        }
        var emptyEvidence = artifact(json.writeValueAsBytes(root));
        var empty = parser.parse(emptyEvidence);
        assertThat(empty.facts()).isEmpty();
        assertThat(empty.evidenceArtifactId()).isEqualTo(emptyEvidence.getId());
    }

    @Test
    void structuralFailuresDoNotEchoRawPayloadAndLeaveItUnchanged() {
        byte[] raw = "{\"PRIVATE_MARKER\": \"not-for-diagnostics\"}".getBytes(StandardCharsets.UTF_8);
        EvidenceArtifact evidence = artifact(raw);
        String digest = evidence.getSha256();
        assertThatIllegalArgumentException().isThrownBy(() -> parser.parse(evidence))
                .withMessageNotContaining("PRIVATE_MARKER").withMessageNotContaining("not-for-diagnostics");
        assertThat(evidence.getPayload()).containsExactly(raw);
        assertThat(evidence.getSha256()).isEqualTo(digest);
    }
}
