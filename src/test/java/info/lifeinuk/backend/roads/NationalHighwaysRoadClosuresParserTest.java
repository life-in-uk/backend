package info.lifeinuk.backend.roads;

import info.lifeinuk.backend.evidence.*;
import info.lifeinuk.backend.source.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static info.lifeinuk.backend.roads.NationalHighwaysRoadClosuresInterpretation.*;

class NationalHighwaysRoadClosuresParserTest {
    private final NationalHighwaysRoadClosuresParser parser = new NationalHighwaysRoadClosuresParser();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String MINIMAL = "{\"D2Payload\":{\"feedType\":\"SituationPublication\",\"situation\":[{\"idG\":\"s\",\"situationRecord\":[{\"sitRoadOrCarriagewayOrLaneManagement\":{\"idG\":\"r\"}}]}]}}";
    private static final String QUERY = "closureType=unplanned&startDateTime=2026-01-01T00:00:00&endDateTime=2026-01-01T06:00:00";

    private static byte[] fixture() throws Exception {
        try(var in = NationalHighwaysRoadClosuresParserTest.class.getResourceAsStream("/nationalhighways/interpretation-page.json")) { return in.readAllBytes(); }
    }
    private EvidenceArtifact artifact(byte[] payload) {
        var endpoint = SourceEndpoint.nationalHighwaysRoadClosures(Source.nationalHighways());
        endpoint.qualify("Offline approval", "Offline terms policy");
        var run = IngestionRun.start(endpoint, Instant.EPOCH);
        var artifact = new EvidenceArtifact(run, new CapturedResponse(payload,"application/json",Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1),200,QUERY,1));
        run.succeed(Instant.EPOCH.plusSeconds(2));
        return artifact;
    }
    private NationalHighwaysRoadClosuresInterpretation parse(String payload) { return parser.parse(artifact(payload.getBytes(StandardCharsets.UTF_8))); }

    @Test
    void representativeFactsGeometryMultiplicityAndProvenanceSurviveExactly() throws Exception {
        var evidence = artifact(fixture());
        var bytes = evidence.getPayload(); var hash = evidence.getSha256();
        var result = parser.parse(evidence);
        assertThat(result.evidenceArtifactId()).isEqualTo(evidence.getId());
        assertThat(result.ingestionRunId()).isEqualTo(evidence.getIngestionRun().getId());
        assertThat(result.observedAt()).isEqualTo(evidence.getObservedAt());
        assertThat(result.requestedAt()).isEqualTo(evidence.getRequestedAt());
        assertThat(result.pageNumber()).isEqualTo(1);
        assertThat(result.requestQuery()).isEqualTo(QUERY);
        assertThat(result.modelVersion()).contains("3.4");
        assertThat(result.publicationTime()).contains(Instant.parse("2026-10-04T03:30:00.123Z"));
        assertThat(result.situations()).extracting(Situation::id).containsExactly("offline-situation","offline-group-situation");
        var situation = result.situations().getFirst();
        assertThat(situation.versionTime()).contains(Instant.parse("2026-10-04T02:00:00Z"));
        assertThat(situation.informationStatus()).contains("real");
        assertThat(situation.confidentiality()).contains("noRestriction");
        assertThat(situation.records()).hasSize(2);
        assertThat(situation.records().get(1)).isEqualTo(situation.records().getFirst());
        var record = situation.records().getFirst();
        assertThat(record.id()).isEqualTo("offline-record");
        assertThat(record.version()).contains("007");
        assertThat(record.creationTime()).contains(Instant.parse("2026-10-04T01:02:03.123456789Z"));
        assertThat(record.versionTime()).contains(Instant.parse("2026-10-04T01:02:03Z"));
        assertThat(record.validity()).contains(new Validity(Optional.of("suspended"),Optional.of(Instant.parse("2026-10-04T00:00:00Z")),Optional.of(Instant.parse("2026-10-04T03:00:00Z"))));
        assertThat(record.probability()).contains("certain");
        assertThat(record.compliance()).contains("mandatory");
        assertThat(record.sourceIdentification()).contains("Offline signals");
        assertThat(record.managementType()).contains(new Code("laneClosures",Optional.empty()));
        assertThat(record.cause().orElseThrow().type()).contains("roadOrCarriagewayOrLaneManagement");
        assertThat(record.cause().orElseThrow().managementType()).contains(new Code("laneClosures",Optional.empty()));
        assertThat(record.publicComments()).containsExactly("  Offline closure — Café, £!  ","  Offline closure — Café, £!  ");
        var location = (LinearLocation)record.locations().getFirst();
        assertThat(location.description()).contains("  Offline M1 — Café!  ");
        var geometry = location.geometry().orElseThrow();
        assertThat(geometry.srsName()).isEqualTo("ESPG::4326");
        assertThat(geometry.dimension()).isEqualTo(2);
        assertThat(geometry.positions()).containsExactly(
                List.of(new BigDecimal("51.123456789012345"),new BigDecimal("-1.50")),
                List.of(new BigDecimal("51.20"),new BigDecimal("-1.60")),
                List.of(new BigDecimal("51.123456789012345"),new BigDecimal("-1.50")));
        assertThat(geometry.sourcePositionList()).contains("  ","\n");
        var carriageway = location.carriageways().getFirst();
        assertThat(carriageway.type()).contains(new Code("extendedG",Optional.of("dualCarriageway")));
        assertThat(carriageway.restrictedLanes()).contains(2); assertThat(carriageway.operationalLanes()).contains(1);
        assertThat(carriageway.lanes()).extracting(Lane::number).containsExactly(Optional.of(2),Optional.of(1),Optional.of(2));
        assertThat(carriageway.lanes().getFirst().usage()).contains(new Code("extendedG",Optional.of("cl2")));
        assertThat(carriageway.lanes().getFirst().status()).contains("Closed");
        assertThat(carriageway.lanes().getFirst().impactDirection()).contains("aligned");
        var road = location.roadSections().getFirst();
        assertThat(road.roadName()).contains(" M1 "); assertThat(road.direction()).contains("northBound");
        assertThat(road.relativeDirection()).contains("aligned");
        assertThat(road.heightGrade()).contains(new Code("extendedG",Optional.of("start1End1")));
        assertThat(road.elementId()).contains("offline-element"); assertThat(road.referenceModel()).contains("The Network Model");
        assertThat(road.elementType()).contains("aCarriageway");
        assertThat(road.fromDistance()).contains(new BigDecimal("12.5")); assertThat(road.toDistance()).contains(new BigDecimal("3.25"));
        var group = (LocationGroup)result.situations().get(1).records().getFirst().locations().getFirst();
        assertThat(group.locations()).containsExactly(location,location);
        assertThat(parser.parse(evidence)).isEqualTo(result);
        assertThat(evidence.getPayload()).containsExactly(bytes); assertThat(evidence.getSha256()).isEqualTo(hash);
        assertThat(evidence.getIngestionRun().getStatus()).isEqualTo(RunStatus.SUCCESS);
        assertThatThrownBy(() -> result.situations().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> geometry.positions().getFirst().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void missingOptionalFactsStayAbsentAndEmptyPageIsValid() {
        var record = parse(MINIMAL).situations().getFirst().records().getFirst();
        assertThat(record.version()).isEmpty();assertThat(record.creationTime()).isEmpty();
        assertThat(record.validity()).isEmpty();assertThat(record.cause()).isEmpty();
        assertThat(record.locations()).isEmpty();assertThat(record.publicComments()).isEmpty();
        assertThat(parse("{\"D2Payload\":{\"feedType\":\"SituationPublication\",\"situation\":[]}}").situations()).isEmpty();
    }

    @Test
    void unknownMetadataLeavesKnownFactsUnchanged() throws Exception {
        var node = (ObjectNode) JSON.readTree(fixture());
        var evidence = artifact(fixture()); var original = parser.parse(evidence);
        node.put("extraRoot",true);
        ((ObjectNode)node.at("/D2Payload")).put("newProviderMetadata",17);
        ((ObjectNode)node.at("/D2Payload/situation/0/situationRecord/0")).put("situationNote","metadata");
        ((ObjectNode)node.at("/D2Payload/situation/0/situationRecord/0/sitRoadOrCarriagewayOrLaneManagement")).put("metadata","harmless");
        ((ObjectNode)node.at("/D2Payload/situation/0/situationRecord/0/sitRoadOrCarriagewayOrLaneManagement/locationReference")).put("locationAccuracy","metadata");
        var changed = parser.parse(artifact(JSON.writeValueAsBytes(node)));
        assertThat(changed.situations()).isEqualTo(original.situations());
    }

    static Stream<String> invalidStructures() {
        return Stream.of("{", "[]", "{}", MINIMAL+" {}", MINIMAL+"garbage",
                MINIMAL.replace("\"idG\":\"s\"", "\"idG\":\"s\",\"idG\":\"other\""),
                MINIMAL.replace("\"idG\":\"r\"", "\"idG\":null"),
                MINIMAL.replace("\"idG\":\"s\"", "\"idG\":12"),
                MINIMAL.replace("\"idG\":\"s\"", "\"idG\":\" \""),
                MINIMAL.replace("SituationPublication","WrongFeed"),
                MINIMAL.replace("\"situationRecord\":[", "\"wrongRecords\":["),
                MINIMAL.replace("sitRoadOrCarriagewayOrLaneManagement","sitOtherRecord"));
    }
    @ParameterizedTest @MethodSource("invalidStructures")
    void malformedRequiredStructureFailsExplicitly(String body) {
        assertThatIllegalArgumentException().isThrownBy(() -> parse(body));
    }

    static Stream<String> invalidKnownFields() {
        return Stream.of("\"versionG\":3", "\"validity\":null", "\"generalPublicComment\":{}",
                "\"situationRecordCreationTime\":\"2026-02-30T01:00:00Z\"",
                "\"situationRecordCreationTime\":\"2026-01-01T01:00:00\"",
                "\"situationRecordCreationTime\":\"2026-01-01T23:59:60Z\"",
                "\"locationReference\":{}", "\"locationReference\":{\"locPointLocation\":{}}",
                "\"locationReference\":{\"locSingleRoadLinearLocation\":{\"linearWithinLinearElement\":null}}",
                "\"locationReference\":{\"locLinearLocation\":{\"gmlLineString\":{\"locGmlLineString\":{\"srsDimension\":\"2\",\"srsName\":\"ESPG::4326\",\"posList\":\"1 2 3 4\"}}}}",
                "\"locationReference\":{\"locLinearLocation\":{\"gmlLineString\":{\"locGmlLineString\":{\"srsDimension\":2,\"srsName\":\"ESPG::4326\",\"posList\":\"1 2 3\"}}}}",
                "\"locationReference\":{\"locLinearLocation\":{\"gmlLineString\":{\"locGmlLineString\":{\"srsDimension\":2,\"srsName\":\"ESPG::4326\",\"posList\":\"NaN 2 3 4\"}}}}");
    }
    @ParameterizedTest @MethodSource("invalidKnownFields")
    void malformedConsumedOptionalValuesFail(String field) {
        assertThatIllegalArgumentException().isThrownBy(() -> parse(MINIMAL.replace("\"idG\":\"r\"","\"idG\":\"r\","+field)));
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> wrongTypes() {
        String base = "/D2Payload/situation/0/situationRecord/0/sitRoadOrCarriagewayOrLaneManagement";
        String linear = base + "/locationReference/locLinearLocation";
        String lane = linear + "/supplementaryPositionalDescription/carriageway/0/lane/0";
        return Stream.of(
            org.junit.jupiter.params.provider.Arguments.of(lane,"laneNumber","\"2\""),
            org.junit.jupiter.params.provider.Arguments.of(lane,"laneNumber","2.0"),
            org.junit.jupiter.params.provider.Arguments.of(lane,"laneNumber","-1"),
            org.junit.jupiter.params.provider.Arguments.of(lane,"laneNumber","2147483648"),
            org.junit.jupiter.params.provider.Arguments.of(linear + "/gmlLineString/locGmlLineString","srsDimension","3"),
            org.junit.jupiter.params.provider.Arguments.of(base + "/locationReference/locSingleRoadLinearLocation/linearWithinLinearElement/0/fromPoint/locDistanceFromLinearElementStart","distanceAlong","\"12.5\""),
            org.junit.jupiter.params.provider.Arguments.of(base + "/locationReference/locSingleRoadLinearLocation/linearWithinLinearElement/0/fromPoint/locDistanceFromLinearElementStart","distanceAlong","-1"),
            org.junit.jupiter.params.provider.Arguments.of(base,"locationReference","null"));
    }
    @ParameterizedTest @MethodSource("wrongTypes")
    void malformedLaneAndGeometryFactsCannotBeCoerced(String path,String field,String value) throws Exception {
        var tree=(ObjectNode)JSON.readTree(fixture());
        ((ObjectNode)tree.at(path)).set(field,JSON.readTree(value));
        assertThatIllegalArgumentException().isThrownBy(()->parse(JSON.writeValueAsString(tree)));
    }

    @Test
    void wrongProviderEndpointScopeQualificationAndLifecycleFailBeforeJsonParsing() {
        var evidence = spy(artifact("not json".getBytes(StandardCharsets.UTF_8)));
        var run = spy(evidence.getIngestionRun()); var endpoint = spy(run.getSourceEndpoint());var source = spy(endpoint.getSource());
        doReturn(run).when(evidence).getIngestionRun();doReturn(endpoint).when(run).getSourceEndpoint();doReturn(source).when(endpoint).getSource();
        for(String fault : List.of("provider","scope","endpoint","url","qualification","run")) {
            reset(source,endpoint,run);doReturn(endpoint).when(run).getSourceEndpoint();doReturn(source).when(endpoint).getSource();
            switch(fault) {
                case "provider" -> doReturn("other").when(source).getKey();
                case "scope" -> doReturn("OTHER_SCOPE").when(source).getScope();
                case "endpoint" -> doReturn("other").when(endpoint).getKey();
                case "url" -> doReturn("https://example.invalid").when(endpoint).getUrl();
                case "qualification" -> doReturn(false).when(endpoint).isQualifiedAndEnabled();
                case "run" -> doReturn(RunStatus.FAILED).when(run).getStatus();
            }
            assertThatIllegalArgumentException().isThrownBy(() -> parser.parse(evidence)).withMessageContaining("canonical");
        }
        verify(evidence,never()).getPayload();
    }
}
