package info.lifeinuk.backend.guides;

import info.lifeinuk.backend.acquisition.BankHolidaysAcquisition;
import info.lifeinuk.backend.acquisition.TflUndergroundAcquisition;
import info.lifeinuk.backend.acquisition.NationalHighwaysRoadClosuresAcquisition;
import info.lifeinuk.backend.support.IsolatedPostgres;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.flyway.schemas=guide_evidence_tests", "spring.flyway.default-schema=guide_evidence_tests",
    "spring.jpa.properties.hibernate.default_schema=guide_evidence_tests", "spring.datasource.hikari.schema=guide_evidence_tests"
})
@ContextConfiguration(initializers=IsolatedPostgres.Initializer.class)
class GuideEvidenceImportTest {
    @Autowired GuideImporter importer;
    @Autowired GuideImportReader reader;
    @Autowired GuideQuery query;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean BankHolidaysAcquisition bank;
    @MockitoBean TflUndergroundAcquisition tube;
    @MockitoBean NationalHighwaysRoadClosuresAcquisition roads;
    @LocalServerPort int port;
    final JsonMapper json = JsonMapper.builder().build();
    final HttpClient http = HttpClient.newHttpClient();
    @BeforeEach void clear() {
        jdbc.execute("DROP TRIGGER IF EXISTS evidence_import_fail ON guide_evidence_support");
        jdbc.execute("DROP FUNCTION IF EXISTS evidence_import_fail()");
        jdbc.execute("TRUNCATE guide_evidence_support, guide_evidence, guide_source, guide");
    }
    @AfterEach void noAcquisition() { verifyNoInteractions(bank, tube, roads); }
    GuideImportDefinition fixture() throws Exception { return parse(GuideEvidenceValidationTest.fixture()); }
    GuideImportDefinition parse(String input) { return reader.parse(input.getBytes(StandardCharsets.UTF_8)); }
    String snapshot() {
        return jdbc.queryForObject("""
            SELECT json_build_object(
              'guide',(SELECT json_agg(row_to_json(g) ORDER BY id) FROM guide g),
              'sources',(SELECT json_agg(row_to_json(s) ORDER BY source_order) FROM guide_source s),
              'evidence',(SELECT json_agg(row_to_json(e) ORDER BY evidence_order) FROM guide_evidence e),
              'supports',(SELECT json_agg(row_to_json(s) ORDER BY evidence_id,support_order) FROM guide_evidence_support s))::text
            """, String.class);
    }
    GuideImportDefinition change(GuideImportDefinition input, String content, List<GuideImportDefinition.Source> sources,
            List<GuideImportDefinition.Evidence> evidence) {
        return new GuideImportDefinition(input.slug(), input.category(), input.title(), input.summary(), content,
                input.status(), input.publishedAt(), input.updatedAt().plusSeconds(1), sources, evidence);
    }
    HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void createAndIdenticalReplayPreserveEveryRowAndOrderedSharedSupport() throws Exception {
        var input=fixture(); var created=importer.apply(input);
        assertThat(created.outcome()).isEqualTo(GuideImporter.Outcome.CREATED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_evidence",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_evidence_support",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT source_key FROM guide_evidence_support s JOIN guide_evidence e ON e.id=s.evidence_id WHERE e.editorial_key='example-application' ORDER BY support_order",String.class))
                .containsExactly("official-a","official-b");
        String before=snapshot();var replay=importer.apply(input);
        assertThat(replay.outcome()).isEqualTo(GuideImporter.Outcome.UNCHANGED);
        assertThat(replay.guideId()).isEqualTo(created.guideId());assertThat(snapshot()).isEqualTo(before);
    }
    @Test void repeatedSupportsAndChangedSupportOrderRemainExact() throws Exception {
        var input=fixture();var item=input.evidence().getFirst();var a=item.supports().getFirst();var b=item.supports().get(1);
        var repeated=new GuideImportDefinition.Evidence(item.key(),item.statement(),List.of(a,b,a));
        var first=change(input,input.content(),input.sources(),List.of(repeated,input.evidence().get(1)));
        importer.apply(first);
        var published=new GuideImportDefinition(first.slug(),first.category(),first.title(),first.summary(),first.content(),
                GuideStatus.PUBLISHED,first.updatedAt(),first.updatedAt(),first.sources(),first.evidence());
        importer.apply(published);
        assertThat(query.detail(input.slug()).orElseThrow().evidence().getFirst().supports())
                .extracting(GuideResponse.Support::sourceKey).containsExactly("official-a","official-b","official-a");
        var reordered=new GuideImportDefinition.Evidence(item.key(),item.statement(),List.of(b,a,a));
        importer.apply(new GuideImportDefinition(first.slug(),first.category(),first.title(),first.summary(),first.content(),
                GuideStatus.PUBLISHED,first.updatedAt(),first.updatedAt(),first.sources(),List.of(reordered,input.evidence().get(1))));
        assertThat(query.detail(input.slug()).orElseThrow().evidence().getFirst().supports())
                .extracting(GuideResponse.Support::sourceKey).containsExactly("official-b","official-a","official-a");
    }
    @Test void ordinaryProseHeadingEditsAndParagraphMovementKeepEvidenceAndSourceIdentity() throws Exception {
        var input=fixture();var id=importer.apply(input).guideId();
        var evidenceIds=jdbc.queryForList("SELECT id FROM guide_evidence ORDER BY evidence_order");
        var supportIds=jdbc.queryForList("SELECT id FROM guide_evidence_support ORDER BY evidence_id,support_order");
        var sourceIds=jdbc.queryForList("SELECT id FROM guide_source ORDER BY source_order");
        for(String text:List.of(input.content().replace("经过", "已经"), input.content().replace("# Example", "# New heading"),
                "[查看官方依据](#guide-evidence-example-condition)\n\nMoved paragraph.[查看官方依据](#guide-evidence-example-application)")) {
            assertThat(importer.apply(change(input,text,input.sources(),input.evidence())).guideId()).isEqualTo(id);
            assertThat(jdbc.queryForList("SELECT id FROM guide_evidence ORDER BY evidence_order")).isEqualTo(evidenceIds);
            assertThat(jdbc.queryForList("SELECT id FROM guide_evidence_support ORDER BY evidence_id,support_order")).isEqualTo(supportIds);
            assertThat(jdbc.queryForList("SELECT id FROM guide_source ORDER BY source_order")).isEqualTo(sourceIds);
        }
    }
    @Test void reorderAndUrlChangeRetainEditorialKeysAndReplaceReferencesSafely() throws Exception {
        var input=fixture();var id=importer.apply(input).guideId();
        var a=input.sources().get(1);var b=input.sources().getFirst();
        var updatedA=new GuideImportDefinition.Source(a.key(),a.organisation(),a.title(),"https://example.invalid/new-a",a.accessedAt());
        var changed=change(input,input.content(),List.of(updatedA,b),input.evidence());
        assertThat(importer.apply(changed).guideId()).isEqualTo(id);
        assertThat(jdbc.queryForList("SELECT editorial_key FROM guide_source ORDER BY source_order",String.class)).containsExactly("official-a","official-b");
        assertThat(jdbc.queryForObject("SELECT url FROM guide_source WHERE editorial_key='official-a'",String.class)).isEqualTo(updatedA.url());
        String before=snapshot();assertThat(importer.apply(changed).outcome()).isEqualTo(GuideImporter.Outcome.UNCHANGED);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void referencedSourceRemovalAndOmittedExistingEvidenceFailWithoutMutation() throws Exception {
        var input=fixture();importer.apply(input);String before=snapshot();
        assertThatIllegalArgumentException().isThrownBy(() -> importer.apply(change(input,input.content(),List.of(input.sources().getFirst()),input.evidence())));
        assertThatIllegalArgumentException().isThrownBy(() -> importer.apply(change(input,"No markers",input.sources(),null)))
                .withMessageContaining("explicit evidence");
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void deliberateEvidenceAndSourceRemovalAndReplacementDoNotLeaveStaleRows() throws Exception {
        var input=fixture();importer.apply(input);
        var remaining=input.evidence().get(1);
        importer.apply(change(input,"[依据](#guide-evidence-example-condition)",List.of(input.sources().get(1)),List.of(remaining)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_source",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_evidence",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_evidence_support",Integer.class)).isEqualTo(1);
        var replacement=new GuideImportDefinition.Evidence("replacement", "Updated claim", remaining.supports());
        importer.apply(change(input,"[依据](#guide-evidence-replacement)",List.of(input.sources().get(1)),List.of(replacement)));
        assertThat(jdbc.queryForObject("SELECT editorial_key FROM guide_evidence",String.class)).isEqualTo("replacement");
        importer.apply(change(input,"No actionable examples",List.of(),List.of()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_evidence_support",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_evidence",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_source",Integer.class)).isZero();
    }
    @Test void supportInsertFailureRollsBackGuideSourcesEvidenceAndPublication() throws Exception {
        var input=fixture();importer.apply(input);String before=snapshot();
        jdbc.execute("CREATE FUNCTION evidence_import_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Offline failure'; END $$");
        jdbc.execute("CREATE TRIGGER evidence_import_fail BEFORE INSERT ON guide_evidence_support FOR EACH ROW EXECUTE FUNCTION evidence_import_fail()");
        var root=(ObjectNode)json.readTree(GuideEvidenceValidationTest.fixture());root.put("status","PUBLISHED");root.put("publishedAt","2026-10-04T12:00:00Z");
        ((ObjectNode)root.get("sources").get(0)).put("url","https://example.invalid/replacement");
        assertThatThrownBy(() -> importer.apply(parse(json.writeValueAsString(root)))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(snapshot()).isEqualTo(before);assertThat(get("/api/guides/"+input.slug()).statusCode()).isEqualTo(404);
        jdbc.execute("DROP TRIGGER evidence_import_fail ON guide_evidence_support");
        jdbc.execute("TRUNCATE guide_evidence_support, guide_evidence, guide_source, guide");
        jdbc.execute("CREATE TRIGGER evidence_import_fail BEFORE INSERT ON guide_evidence_support FOR EACH ROW EXECUTE FUNCTION evidence_import_fail()");
        assertThatThrownBy(() -> importer.apply(input)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_source",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_evidence",Integer.class)).isZero();
    }
    @Test void draftHiddenPublishedDetailResolvesKeysWhileListContractStaysIdenticalAndReadsDoNotWrite() throws Exception {
        var input=fixture();importer.apply(input);
        assertThat(get("/api/guides/"+input.slug()).statusCode()).isEqualTo(404);
        var root=(ObjectNode)json.readTree(GuideEvidenceValidationTest.fixture());root.put("status","PUBLISHED");root.put("publishedAt","2026-10-04T12:00:00Z");
        importer.apply(parse(json.writeValueAsString(root)));String before=snapshot();
        var response=get("/api/guides/"+input.slug());assertThat(response.statusCode()).isEqualTo(200);var body=json.readTree(response.body());
        assertThat(body.get("content").asString()).isEqualTo(input.content());
        assertThat(body.get("sources").get(0).get("key").asString()).isEqualTo("official-b");
        assertThat(body.get("sources").get(1).get("organisation").asString()).isEqualTo("Example A");
        assertThat(body.get("sources").get(1).get("url").asString()).isEqualTo("https://example.invalid/a");
        assertThat(body.get("sources").get(1).get("accessedAt").asString()).isEqualTo("2026-10-04T10:00:00Z");
        var evidence=body.get("evidence");assertThat(evidence.size()).isEqualTo(2);
        assertThat(evidence.get(0).get("statement").asString()).isEqualTo(input.evidence().getFirst().statement());
        var links=evidence.get(0).get("supports");
        assertThat(links.get(0).get("sourceKey").asString()).isEqualTo("official-a");
        assertThat(links.get(0).get("locator").asString()).isEqualTo("Application section");
        assertThat(links.get(0).get("excerpt").isNull()).isTrue();
        assertThat(links.get(1).get("excerpt").asString()).isEqualTo("Synthetic quotation only.");
        assertThat(links.get(1).get("note").asString()).isEqualTo("Additional conditions — 示例。");
        assertThat(response.body()).doesNotContain("\"id\"", "guideId", "evidenceOrder", "supportOrder");
        var list=json.readTree(get("/api/guides").body());
        assertThat(list.get(0).properties()).hasSize(6);
        assertThat(list.toString()).doesNotContain("\"content\"", "\"sources\"", "\"evidence\"", "\"supports\"");
        var dto=query.detail(input.slug()).orElseThrow();
        assertThatThrownBy(() -> dto.evidence().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> dto.evidence().getFirst().supports().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void sourceUrlsAreOnlyMetadataAndNeverFetchedDuringImportOrRead() throws Exception {
        var calls=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {calls.incrementAndGet();exchange.sendResponseHeaders(500,-1);exchange.close();});server.start();
        try {
            String document=GuideEvidenceValidationTest.fixture().replace("https://example.invalid/b","http://127.0.0.1:"+server.getAddress().getPort()+"/b")
                    .replace("\"status\": \"DRAFT\"","\"status\": \"PUBLISHED\"").replace("\"publishedAt\": null","\"publishedAt\": \"2026-10-04T12:00:00Z\"");
            var input=parse(document);importer.apply(input);get("/api/guides/"+input.slug());get("/api/guides");
            assertThat(calls.get()).isZero();
        } finally {server.stop(0);}
    }
    @Test void databaseEnforcesSameGuideReferencesAndBlocksReferencedSourceDeletion() throws Exception {
        var input=fixture();var guideId=importer.apply(input).guideId();
        var other=new GuideImportDefinition("other-guide",input.category(),input.title(),input.summary(),"No evidence",GuideStatus.DRAFT,null,input.updatedAt(),List.of());
        var otherId=importer.apply(other).guideId();
        var evidenceId=jdbc.queryForObject("SELECT id FROM guide_evidence WHERE editorial_key='example-application'",java.util.UUID.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE guide_evidence_support SET guide_id=? WHERE evidence_id=?",otherId,evidenceId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM guide_source WHERE guide_id=? AND editorial_key='official-a'",guideId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE guide_source SET editorial_key='official-a' WHERE editorial_key='official-b'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
