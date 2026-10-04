package info.lifeinuk.backend.roads;

import info.lifeinuk.backend.acquisition.NationalHighwaysRoadClosuresAcquisition;
import info.lifeinuk.backend.evidence.CapturedResponse;
import info.lifeinuk.backend.evidence.EvidenceArtifact;
import info.lifeinuk.backend.evidence.IngestionRun;
import info.lifeinuk.backend.evidence.NationalHighwaysRunEvidence;
import info.lifeinuk.backend.source.SourceEndpoint;
import info.lifeinuk.backend.support.IsolatedPostgres;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static info.lifeinuk.backend.roads.RoadsCurrentStateProjector.Outcome.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.flyway.schemas=roads_current_tests", "spring.flyway.default-schema=roads_current_tests",
    "spring.jpa.properties.hibernate.default_schema=roads_current_tests", "spring.datasource.hikari.schema=roads_current_tests"
})
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
class RoadsCurrentStateApiTest {
    static final String WINDOW = "closureType=unplanned&startDateTime=2026-10-04T06:00:00&endDateTime=2026-10-04T12:00:00";
    static final Instant START = Instant.parse("2026-10-04T12:00:00.123456Z");
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired RoadsEvidenceProjection projection;
    @Autowired RoadsCurrentStateProjector projector;
    @Autowired NationalHighwaysRunEvidence evidence;
    @MockitoSpyBean RoadsCurrentStates states;
    @MockitoSpyBean RoadsCurrentStateStore store;
    @MockitoSpyBean NationalHighwaysRoadClosuresParser parser;
    @MockitoBean NationalHighwaysRoadClosuresAcquisition acquisition;
    @LocalServerPort int port;
    final JsonMapper json = JsonMapper.builder().build();
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetState() {
        reset(store, states, parser);
        jdbc.execute("DROP TRIGGER IF EXISTS roads_fail ON roads_current_snapshot");
        jdbc.execute("DROP FUNCTION IF EXISTS roads_fail()");
        jdbc.execute("TRUNCATE roads_current_snapshot, evidence_artifact, ingestion_run");
        jdbc.update("UPDATE source_endpoint SET enabled=true, qualification_status='QUALIFIED',qualification_record='Offline owner',use_retention_policy='Offline policy' WHERE endpoint_key='national-highways-road-closures'");
    }

    UUID run(Instant time, boolean success, List<String> payloads) {
        return run(time,success,payloads,null);
    }
    UUID run(Instant time, boolean success, List<String> payloads, List<String> queries) {
        return new TransactionTemplate(transactions).execute(status -> {
            var endpoint = em.createQuery("SELECT e FROM SourceEndpoint e JOIN FETCH e.source WHERE e.key=:key",SourceEndpoint.class)
                    .setParameter("key",SourceEndpoint.NATIONAL_HIGHWAYS_ROAD_CLOSURES_KEY).getSingleResult();
            var run = IngestionRun.start(endpoint,time); em.persist(run);
            for(int page=0;page<payloads.size();page++) {
                var artifact = new EvidenceArtifact(run,new CapturedResponse(payloads.get(page).getBytes(StandardCharsets.UTF_8),
                        "application/json",time.plusSeconds(page+1),time.plusSeconds(page+2),200,
                        queries==null?WINDOW+(page==0?"":"&pageCursor=offline-"+page):queries.get(page),page+1));
                em.persist(artifact);
            }
            if(success) run.succeed(time.plusSeconds(20)); else run.fail(time.plusSeconds(20),"OFFLINE","Offline failure");
            em.flush(); return run.getId();
        });
    }

    String payload(String id) {
        return """
            {"D2Payload":{"feedType":"SituationPublication","situation":[{"idG":"situation-%s","situationRecord":[{
             "sitRoadOrCarriagewayOrLaneManagement":{"idG":"%s","versionG":"007",
              "generalPublicComment":[{"comment":"  Café! M1 — £  "},{"comment":"Second source comment"},{"comment":"  Café! M1 — £  "}],
              "roadOrCarriagewayOrLaneManagementType":{"value":"extendedG","extendedValueG":"closure"},
              "validity":{"validityStatus":"active","validityTimeSpecification":{"overallStartTime":"2026-10-04T11:00:00Z"}},
              "cause":{"causeType":"accident"},
              "locationReference":{"locLinearLocation":{"gmlLineString":{"locGmlLineString":{
                "srsName":"ESPG::4326","srsDimension":2,"posList":"52.193516 -0.908380 52.193682 -0.908629"}},
                "supplementaryPositionalDescription":{"locationDescription":"M1 northbound between J15 and J15A"}},
               "locSingleRoadLinearLocation":{"linearWithinLinearElement":[{"directionOnLinearSection":"northBound",
                 "linearElement":{"locLinearElementByCode":{"roadName":" M1 "}}}]}}
             }}]}]}}
            """.formatted(id,id);
    }
    String empty() { return "{\"D2Payload\":{\"feedType\":\"SituationPublication\",\"situation\":[]}}"; }
    String stored() { return jdbc.queryForObject("SELECT row_to_json(s)::text FROM roads_current_snapshot s",String.class); }
    void removeRawEvidence(UUID run, boolean firstPageOnly) {
        // Test-only simulation of future reviewed cleanup. Production V2 immutability is unchanged.
        jdbc.execute("ALTER TABLE evidence_artifact DISABLE TRIGGER evidence_artifact_history");
        try { jdbc.update("DELETE FROM evidence_artifact WHERE ingestion_run_id=?"+(firstPageOnly?" AND page_number=1":""),run); }
        finally { jdbc.execute("ALTER TABLE evidence_artifact ENABLE TRIGGER evidence_artifact_history"); }
    }
    String history() {
        return jdbc.queryForObject("""
            SELECT json_build_object('runs',(SELECT json_agg(row_to_json(r) ORDER BY id) FROM ingestion_run r),
            'artifacts',(SELECT json_agg(row_to_json(a) ORDER BY id) FROM evidence_artifact a),
            'endpoints',(SELECT json_agg(row_to_json(e) ORDER BY id) FROM source_endpoint e),
            'sources',(SELECT json_agg(row_to_json(s) ORDER BY id) FROM source s))::text
            """,String.class);
    }
    HttpResponse<String> get(String query) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/travel/roads"+query))
                .timeout(java.time.Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void completeMultipageLogicalRunPersistsAllOrderedRepeatedFactsAndProvenance() throws Exception {
        String representative;
        try(var in=getClass().getResourceAsStream("/nationalhighways/interpretation-page.json")){representative=new String(in.readAllBytes(),StandardCharsets.UTF_8);}
        UUID run=run(START,true,List.of(representative,representative));
        String before=history();
        assertThat(projection.projectRun(run)).isEqualTo(APPLIED);
        var current=states.current().orElseThrow();
        assertThat(current.ingestionRunId()).isEqualTo(run);
        assertThat(current.sourceEndpointId()).isEqualTo(jdbc.queryForObject("SELECT source_endpoint_id FROM ingestion_run WHERE id=?",UUID.class,run));
        assertThat(current.snapshotAt()).isEqualTo(START);
        assertThat(current.projectedAt()).isAfter(START);
        assertThat(current.pageCount()).isEqualTo(2);
        var parsed=parser.parse(evidence.requireSuccessful(run).pages().getFirst());
        var expected=parsed.situations().stream().flatMap(s->s.records().stream().map(c->new RoadsCurrentState.Record(s.id(),s.versionTime(),s.confidentiality(),s.informationStatus(),c))).toList();
        assertThat(current.closures()).containsExactlyElementsOf(java.util.stream.Stream.concat(expected.stream(),expected.stream()).toList());
        assertThat(history()).isEqualTo(before);
        assertThat(projection.projectRun(run)).isEqualTo(REPLAYED);
        // Fresh concrete store instance proves facts are persisted, including grouped/decimal geometry and optional values.
        assertThat(new RoadsCurrentStateStore(jdbc).read(false)).contains(current);
    }

    @Test
    void newerReplacesMissingRecordsOlderCannotWinAndEmptyIsDistinctFromUnavailable() {
        assertThat(states.current()).isEmpty();
        UUID first=run(START,true,List.of(payload("a"),payload("b")));
        assertThat(projection.projectRun(first)).isEqualTo(APPLIED);
        UUID newer=run(START.plusSeconds(30),true,List.of(payload("b")));
        assertThat(projection.projectRun(newer)).isEqualTo(APPLIED);
        assertThat(states.current().orElseThrow().closures()).extracting(r->r.closure().id()).containsExactly("b");
        String before=stored();
        assertThat(projection.projectRun(first)).isEqualTo(IGNORED_OLDER);
        assertThat(projection.projectRun(newer)).isEqualTo(REPLAYED);
        assertThat(stored()).isEqualTo(before);
        UUID empty=run(START.plusSeconds(60),true,List.of(empty()));
        assertThat(projection.projectRun(empty)).isEqualTo(APPLIED);
        assertThat(states.current().orElseThrow().closures()).isEmpty();
        assertThat(states.current().orElseThrow().ingestionRunId()).isEqualTo(empty);
    }

    @Test
    void equalTimestampDifferentRunAndInconsistentReplayConflictWithoutChangingAnything() {
        UUID a=run(START,true,List.of(payload("a"))),b=run(START,true,List.of(payload("b")));
        projection.projectRun(a); String before=stored();
        assertThat(projection.projectRun(b)).isEqualTo(CONFLICT);
        var state=states.current().orElseThrow();
        assertThat(projector.project(new RoadsCurrentState(a,state.sourceEndpointId(),state.snapshotAt(),Instant.now(),1,List.of()))).isEqualTo(CONFLICT);
        assertThat(projector.project(new RoadsCurrentState(a,state.sourceEndpointId(),state.snapshotAt().plusNanos(1),Instant.now(),1,state.closures()))).isEqualTo(CONFLICT);
        assertThat(stored()).isEqualTo(before);
    }

    @Test
    void failedWrongProviderAndMissingPageRunCannotProject() {
        var failed=run(START,false,List.of(payload("a")));
        assertThatIllegalArgumentException().isThrownBy(()->projection.projectRun(failed));
        assertThatIllegalArgumentException().isThrownBy(()->projection.projectRun(UUID.randomUUID()));
        var noPages=run(START,true,List.of());
        assertThatIllegalArgumentException().isThrownBy(()->projection.projectRun(noPages));
        UUID other=new TransactionTemplate(transactions).execute(status->{
            jdbc.update("UPDATE source_endpoint SET qualification_status='QUALIFIED',qualification_record='Offline owner',use_retention_policy='Offline policy' WHERE endpoint_key='gov-uk-bank-holidays-json'");
            var endpoint=em.createQuery("SELECT e FROM SourceEndpoint e JOIN FETCH e.source WHERE e.key=:key",SourceEndpoint.class)
                    .setParameter("key",SourceEndpoint.BANK_HOLIDAYS_KEY).getSingleResult();
            var run=IngestionRun.start(endpoint,START);em.persist(run);run.succeed(START.plusSeconds(1));em.flush();return run.getId();
        });
        assertThatIllegalArgumentException().isThrownBy(()->projection.projectRun(other));
        assertThat(states.current()).isEmpty();
        verifyNoInteractions(acquisition);
    }

    @Test
    void missingQualificationAndGappedPagesFailClosedWithoutChangingExistingState() {
        var original=run(START,true,List.of(payload("original"))); projection.projectRun(original);String before=stored();
        var missing=run(START.plusSeconds(1),true,List.of(payload("a"),payload("b")));
        // Simulate future raw retention removing one page; never replace Current State from a partial surviving run.
        removeRawEvidence(missing,true);
        assertThatIllegalArgumentException().isThrownBy(()->projection.projectRun(missing));
        jdbc.update("UPDATE source_endpoint SET qualification_status='PENDING',qualification_record=NULL WHERE endpoint_key='national-highways-road-closures'");
        assertThatIllegalArgumentException().isThrownBy(()->projection.projectRun(original));
        assertThat(stored()).isEqualTo(before);
    }

    @Test
    void malformedLaterPageAndDatabaseReplacementFailureRollBackEverything() {
        var original=run(START,true,List.of(payload("original")));projection.projectRun(original);String before=stored();
        var malformed=run(START.plusSeconds(1),true,List.of(payload("a"),"{}"));
        assertThatIllegalArgumentException().isThrownBy(()->projection.projectRun(malformed));assertThat(stored()).isEqualTo(before);
        jdbc.execute("""
            CREATE FUNCTION roads_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Offline failure'; END $$
            """);
        jdbc.execute("CREATE TRIGGER roads_fail BEFORE UPDATE ON roads_current_snapshot FOR EACH ROW EXECUTE FUNCTION roads_fail()");
        var newer=run(START.plusSeconds(2),true,List.of(payload("new")));
        assertThatThrownBy(()->projection.projectRun(newer)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(stored()).isEqualTo(before);
    }

    @Test
    void bothFirstWriterArrivalOrdersAndExistingStateRacesCannotEndStale() throws Exception {
        for(boolean existing:List.of(false,true)) {
            for(boolean newerFirst:List.of(false,true)) {
                jdbc.execute("TRUNCATE roads_current_snapshot");
                if(existing) projection.projectRun(run(START.minusSeconds(30),true,List.of(payload("seed"))));
                var old=run(START,true,List.of(payload("old")));
                var newer=run(START.plusSeconds(30),true,List.of(payload("new")));
                var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
                var first=new java.util.concurrent.atomic.AtomicBoolean(true);
                doAnswer(invocation->{
                    var result=invocation.callRealMethod();
                    if(first.getAndSet(false)) {locked.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();}
                    return result;
                }).when(store).read(true);
                try(var pool=Executors.newFixedThreadPool(2)) {
                    var a=pool.submit(()->projection.projectRun(newerFirst?newer:old));
                    assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
                    var b=pool.submit(()->projection.projectRun(newerFirst?old:newer));
                    try {
                        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                        boolean blocked=false;
                        while(System.nanoTime()<deadline) {
                            blocked=jdbc.queryForObject("SELECT count(*)>0 FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%roads_current_snapshot%'",Boolean.class);
                            if(blocked) break;
                            Thread.sleep(10);
                        }
                        assertThat(blocked).as("second writer actually waits for the database lock").isTrue();
                    } finally {release.countDown();}
                    a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
                }
                reset(store);
                assertThat(states.current().orElseThrow().ingestionRunId()).isEqualTo(newer);
                assertThat(states.current().orElseThrow().closures()).extracting(r->r.closure().id()).containsExactly("new");
            }
        }
    }

    @Test
    void pageWindowAndContinuationValidationRejectsIncoherentOrPlannedSnapshots() {
        for(var queries:List.of(
                List.of(WINDOW+"&pageCursor=first"),
                List.of(WINDOW.replace("unplanned","planned")),
                List.of(WINDOW.replace("06:00:00","07:00:00")),
                List.of(WINDOW,WINDOW.replace("06:00:00","07:00:00").replace("12:00:00","13:00:00")+"&pageCursor=second"),
                List.of(WINDOW,WINDOW),
                List.of(WINDOW+"&modifiedSinceDateTime=2026-10-04T11:00:00"))) {
            var payloads=queries.stream().map(q->payload("a")).toList();
            var id=run(START,true,payloads,queries);
            assertThatIllegalArgumentException().isThrownBy(()->projection.projectRun(id));
        }
        assertThat(states.current()).isEmpty();
    }

    @Test
    void emptyFirstSnapshotAndConcurrentEqualTimeRunsHaveExplicitOutcomes() throws Exception {
        UUID empty=run(START,true,List.of(empty()));
        assertThat(projection.projectRun(empty)).isEqualTo(APPLIED);
        assertThat(states.current().orElseThrow().closures()).isEmpty();
        jdbc.execute("TRUNCATE roads_current_snapshot");
        var a=run(START,true,List.of(payload("a")));var b=run(START,true,List.of(payload("b")));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var first=pool.submit(()->{gate.await();return projection.projectRun(a);});
            var second=pool.submit(()->{gate.await();return projection.projectRun(b);});gate.countDown();
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(APPLIED,CONFLICT);
        }
        var state=states.current().orElseThrow();
        assertThat(state.closures().getFirst().closure().id()).isEqualTo(state.ingestionRunId().equals(a)?"a":"b");
    }

    @Test
    void nearestFirstBeatsIdentityOrderingAndUnlocatedRecordsAreNotMatchedByText() throws Exception {
        String farther=payload("a").replace("52.193516 -0.908380 52.193682 -0.908629","52.2 -0.908380 52.21 -0.908629");
        String unlocated=payload("unlocated").replace("\"ESPG::4326\"","\"unsupported-crs\"");
        projection.projectRun(run(START,true,List.of(farther,payload("z"),payload("z"),unlocated)));
        var results=json.readTree(get("?lat=52.193516&lon=-0.908380").body()).get("disruptions");
        assertThat(results.size()).isEqualTo(3);
        assertThat(results.get(0).get("recordId").asString()).isEqualTo("z");
        assertThat(results.get(1)).isEqualTo(results.get(0));
        assertThat(results.get(2).get("recordId").asString()).isEqualTo("a");
        assertThat(results.get(2).get("distanceMeters").asDouble()).isGreaterThan(results.get(0).get("distanceMeters").asDouble());
    }

    @ParameterizedTest
    @ValueSource(strings={"POST","PUT","PATCH","DELETE"})
    void noWriteHttpMethodsExist(String method) throws Exception {
        clearInvocations(states,parser,acquisition);
        var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/travel/roads?lat=52&lon=-1"))
                .method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(405);verifyNoInteractions(states,parser,acquisition);
    }

    @Test
    void readersSeeOldCompleteSnapshotDuringUncommittedReplacementThenNewCompleteSnapshot() throws Exception {
        var old=run(START,true,List.of(payload("old"))); projection.projectRun(old);
        var newer=run(START.plusSeconds(1),true,List.of(payload("new")));
        var written=new CountDownLatch(1);var commit=new CountDownLatch(1);
        doAnswer(invocation->{invocation.callRealMethod();written.countDown();assertThat(commit.await(10,TimeUnit.SECONDS)).isTrue();return null;})
                .when(store).replace(any());
        try(var pool=Executors.newSingleThreadExecutor()) {
            var writer=pool.submit(()->projection.projectRun(newer));
            assertThat(written.await(10,TimeUnit.SECONDS)).isTrue();
            try {
                var state=states.current().orElseThrow();
                assertThat(state.ingestionRunId()).isEqualTo(old);
                assertThat(state.closures()).extracting(r->r.closure().id()).containsExactly("old");
            } finally {commit.countDown();}
            assertThat(writer.get(10,TimeUnit.SECONDS)).isEqualTo(APPLIED);
        }
        assertThat(states.current().orElseThrow().closures()).extracting(r->r.closure().id()).containsExactly("new");
    }

    @Test
    void apiPreservesProviderFactsRepeatedValuesAndOrderingWithoutRawEvidenceAndIsReadOnly() throws Exception {
        var run=run(START,true,List.of(payload("b"),payload("a")));projection.projectRun(run);
        // Raw evidence deletion must neither prevent nor break the Current State read.
        removeRawEvidence(run,false);
        String before=stored(),history=history();clearInvocations(parser,states,acquisition);
        var response=get("?lat=52.193516&lon=-0.908380");assertThat(response.statusCode()).isEqualTo(200);
        var body=json.readTree(response.body());
        assertThat(body.get("snapshotAt").asString()).isEqualTo(START.toString());
        assertThat(body.get("relevanceRadiusMeters").asDouble()).isEqualTo(15000);
        var records=body.get("disruptions");assertThat(records.size()).isEqualTo(2);
        assertThat(records.get(0).get("recordId").asString()).isEqualTo("a");
        assertThat(records.get(1).get("recordId").asString()).isEqualTo("b");
        var record=records.get(0);
        assertThat(record.get("recordVersion").asString()).isEqualTo("007");
        assertThat(record.get("descriptions").get(0).asString()).isEqualTo("  Café! M1 — £  ");
        assertThat(record.get("descriptions").get(1).asString()).isEqualTo("Second source comment");
        assertThat(record.get("descriptions").get(2)).isEqualTo(record.get("descriptions").get(0));
        assertThat(record.get("type").get("extendedValue").asString()).isEqualTo("closure");
        assertThat(record.get("cause").get("type").asString()).isEqualTo("accident");
        assertThat(record.get("status").asString()).isEqualTo("active");
        assertThat(record.get("startTime").asString()).isEqualTo("2026-10-04T11:00:00Z");assertThat(record.get("endTime").isNull()).isTrue();
        assertThat(record.get("distanceMeters").asDouble()).isCloseTo(0,within(0.00001));
        var location=record.get("locations").get(0);
        assertThat(location.get("roads").get(0).get("name").asString()).isEqualTo(" M1 ");
        assertThat(location.get("roads").get(0).get("direction").asString()).isEqualTo("northBound");
        assertThat(location.get("coordinates").get(0).get("latitude").asDouble()).isEqualTo(52.193516);
        assertThat(response.body()).doesNotContain(run.toString(),"evidenceArtifact","payload","sha256","sourceEndpointId");
        assertThat(json.readTree(get("?lat=52.193516&lon=-0.908380").body())).isEqualTo(body);
        assertThat(stored()).isEqualTo(before);assertThat(history()).isEqualTo(history);
        verify(states,times(2)).current();verifyNoInteractions(parser,acquisition);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void suspendedRecordIsKeptInCurrentStateButNotReturnedAsACurrentDisruption() throws Exception {
        String suspended=payload("suspended").replace("\"validityStatus\":\"active\"","\"validityStatus\":\"suspended\"");
        assertThat(suspended).contains("\"validityStatus\":\"suspended\"");
        projection.projectRun(run(START,true,List.of(payload("active"),suspended)));
        // Provider facts are preserved unchanged in Current State.
        assertThat(states.current().orElseThrow().closures()).extracting(r -> r.closure().id()).containsExactly("active","suspended");
        assertThat(stored()).contains("\"suspended\"");
        var records=json.readTree(get("?lat=52.193516&lon=-0.908380").body()).get("disruptions");
        assertThat(records.size()).isEqualTo(1);
        assertThat(records.get(0).get("recordId").asString()).isEqualTo("active");
        verifyNoInteractions(acquisition);
    }

    @Test
    void unavailableEmptyAndOutsideRelevanceHaveDistinctCorrectHttpResults() throws Exception {
        assertThat(get("?lat=52&lon=-1").statusCode()).isEqualTo(404);
        var empty=run(START,true,List.of(empty()));projection.projectRun(empty);
        var response=get("?lat=52&lon=-1");assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("disruptions").isEmpty()).isTrue();
        projection.projectRun(run(START.plusSeconds(30),true,List.of(payload("a"))));
        response=get("?lat=0&lon=0");assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("disruptions").isEmpty()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings={"","?lat=52","?lon=-1","?lat=91&lon=0","?lat=0&lon=-181","?lat=NaN&lon=0",
            "?lat=0&lon=Infinity","?lat=banana&lon=0","?lat=&lon=0"})
    void missingOrInvalidLocationIsBoundedClientErrorAndNeverReadsNationwideState(String query) throws Exception {
        clearInvocations(states,parser,acquisition);
        var response=get(query);assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("ROADS_LOCATION_INVALID");
        assertThat(response.body()).doesNotContain("disruptions","Exception","SQL");
        verifyNoInteractions(states,parser,acquisition);
    }

    @Test
    void unexpectedReadFailureHasBounded500AndNoLeakedDiagnostics() throws Exception {
        doThrow(new IllegalStateException("SECRET SQL /local/path")).when(states).current();
        var response=get("?lat=52&lon=-1");assertThat(response.statusCode()).isEqualTo(500);
        assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("ROADS_READ_FAILED");
        assertThat(response.body()).doesNotContain("SECRET","SQL","/local","Exception");
    }

    @Test
    void v7AddsOnlyAnEmptyIndependentSnapshotTableAndPreservesExistingV6Rows() throws Exception {
        String schema="roads_v7_"+UUID.randomUUID().toString().replace("-","");
        var datasource=jdbc.getDataSource();
        org.flywaydb.core.Flyway.configure().dataSource(datasource).schemas(schema).defaultSchema(schema).target("6").load().migrate();
        try(var connection=datasource.getConnection()) {
            connection.setSchema(schema);
            try(var statement=connection.createStatement()) {
                statement.execute("INSERT INTO source VALUES ('00000000-0000-0000-0000-000000000001','national-highways','National Highways','NATIONAL_HIGHWAYS_ROAD_CLOSURES',true,0)");
                statement.execute("""
                    INSERT INTO source_endpoint (id,source_id,endpoint_key,url,qualification_status,qualification_record,attribution_reference,use_retention_policy,enabled,version)
                    VALUES ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','national-highways-road-closures',
                    'https://api.data.nationalhighways.co.uk/roads/v2.0/closures','QUALIFIED','Offline owner','Offline terms','Offline policy',true,0)
                    """);
                statement.execute("INSERT INTO ingestion_run VALUES ('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000002','2026-01-01T00:00:00Z',NULL,'STARTED',NULL,NULL,0)");
                statement.execute("""
                    INSERT INTO evidence_artifact (id,ingestion_run_id,payload,media_type,observed_at,sha256)
                    VALUES ('00000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000003',convert_to('{}','UTF8'),
                    'application/json','2026-01-01T00:00:01Z',encode(sha256(convert_to('{}','UTF8')),'hex'))
                    """);
                statement.execute("UPDATE ingestion_run SET status='SUCCESS',completed_at='2026-01-01T00:00:02Z'");
                String snapshot="""
                    SELECT json_build_object('source',(SELECT json_agg(row_to_json(s)) FROM source s),
                    'endpoint',(SELECT json_agg(row_to_json(e)) FROM source_endpoint e),
                    'run',(SELECT json_agg(row_to_json(r)) FROM ingestion_run r),
                    'evidence',(SELECT json_agg(row_to_json(a)) FROM evidence_artifact a))::text
                    """;
                String before;
                try(var rows=statement.executeQuery(snapshot)){rows.next();before=rows.getString(1);}
                assertThat(org.flywaydb.core.Flyway.configure().dataSource(datasource).schemas(schema).defaultSchema(schema).target("7").load().migrate().migrationsExecuted).isEqualTo(1);
                try(var rows=statement.executeQuery(snapshot)){rows.next();assertThat(rows.getString(1)).isEqualTo(before);}
                try(var rows=statement.executeQuery("SELECT count(*) FROM roads_current_snapshot")){rows.next();assertThat(rows.getInt(1)).isZero();}
                try(var rows=statement.executeQuery("SELECT count(*) FROM pg_constraint WHERE conrelid='roads_current_snapshot'::regclass AND contype='f'")){rows.next();assertThat(rows.getInt(1)).isZero();}
                assertThatThrownBy(()->statement.execute("INSERT INTO roads_current_snapshot VALUES (2,gen_random_uuid(),gen_random_uuid(),0,0,now(),1,'[]')")).isInstanceOf(java.sql.SQLException.class);
                assertThatThrownBy(()->statement.execute("INSERT INTO roads_current_snapshot VALUES (1,gen_random_uuid(),gen_random_uuid(),0,1000000000,now(),1,'[]')")).isInstanceOf(java.sql.SQLException.class);
                assertThatThrownBy(()->statement.execute("INSERT INTO roads_current_snapshot VALUES (1,gen_random_uuid(),gen_random_uuid(),0,0,now(),1,'{}')")).isInstanceOf(java.sql.SQLException.class);
            }
        }
    }

    @Test
    void snapshotNanosRoundTripAndOrderWithoutTimestampTruncation() {
        UUID id=run(START,true,List.of(payload("a")));projection.projectRun(id);
        var state=states.current().orElseThrow();
        var next=new RoadsCurrentState(UUID.randomUUID(),state.sourceEndpointId(),state.snapshotAt().plusNanos(1),Instant.now(),state.pageCount(),state.closures());
        assertThat(projector.project(next)).isEqualTo(APPLIED);
        assertThat(states.current().orElseThrow().snapshotAt()).isEqualTo(START.plusNanos(1));
        assertThat(projector.project(state)).isEqualTo(IGNORED_OLDER);
        assertThat(projector.project(next)).isEqualTo(REPLAYED);
    }

    @Test
    void explicitReplayOptionProjectsOnlyExistingRunAndNormalStartupDoesNothing() {
        var replay=new RoadsRunReplay(projection);
        replay.run(new DefaultApplicationArguments());assertThat(states.current()).isEmpty();
        var run=run(START,true,List.of(payload("a")));
        replay.run(new DefaultApplicationArguments("--project-roads-run="+run));
        assertThat(states.current().orElseThrow().ingestionRunId()).isEqualTo(run);
        replay.run(new DefaultApplicationArguments("--project-roads-run="+run));
        assertThatIllegalArgumentException().isThrownBy(()->replay.run(new DefaultApplicationArguments("--project-roads-run")));
        assertThatIllegalArgumentException().isThrownBy(()->replay.run(new DefaultApplicationArguments("--project-roads-run=invalid")));
        verifyNoInteractions(acquisition);
    }
}
