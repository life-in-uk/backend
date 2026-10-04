package info.lifeinuk.backend.guides;

import com.sun.net.httpserver.HttpServer;
import info.lifeinuk.backend.acquisition.BankHolidaysAcquisition;
import info.lifeinuk.backend.acquisition.NationalHighwaysRoadClosuresAcquisition;
import info.lifeinuk.backend.acquisition.TflUndergroundAcquisition;
import info.lifeinuk.backend.support.IsolatedPostgres;
import jakarta.persistence.EntityManager;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.flyway.schemas=guide_api_tests", "spring.flyway.default-schema=guide_api_tests",
    "spring.jpa.properties.hibernate.default_schema=guide_api_tests", "spring.datasource.hikari.schema=guide_api_tests"
})
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
class GuideApiTest {
    static final Instant TIME = Instant.parse("2026-10-04T12:00:00.123456Z");
    static final String BODY = "# Offline guide — 指南\n\n  Markdown **text**, £, punctuation!  \n\n- First\n- Second\n";
    @Autowired GuideRepository guides;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean GuideQuery query;
    @MockitoBean BankHolidaysAcquisition bankHolidays;
    @MockitoBean TflUndergroundAcquisition underground;
    @MockitoBean NationalHighwaysRoadClosuresAcquisition roads;
    @LocalServerPort int port;
    final JsonMapper json = JsonMapper.builder().build();
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clear() {
        reset(query);
        jdbc.execute("TRUNCATE guide_source, guide");
    }
    @AfterEach
    void noAcquisitionDependency() { verifyNoInteractions(bankHolidays, underground, roads); }

    Guide fixture(String slug, Instant publication) {
        var guide = new Guide(slug,"example-category","  Offline title — 指南!  "," Offline summary ",BODY,TIME);
        if(publication!=null) guide.publish(publication);
        return guides.saveAndFlush(guide);
    }
    HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .timeout(java.time.Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    String snapshot() {
        return jdbc.queryForObject("""
            SELECT json_build_object('guides',(SELECT json_agg(row_to_json(g) ORDER BY id) FROM guide g),
            'sources',(SELECT json_agg(row_to_json(s) ORDER BY guide_id,source_order) FROM guide_source s),
            'runs',(SELECT count(*) FROM ingestion_run),'artifacts',(SELECT count(*) FROM evidence_artifact))::text
            """,String.class);
    }

    @Test
    void persistenceRoundTripsMarkdownOrderedSourcesAndPublicationTimestamps() {
        var created=new Guide("offline-guide","example-category","Title","Summary",BODY,TIME);
        created.addSource("Second organisation","First editorial reference","https://example.invalid/reference-b",TIME.minusSeconds(20));
        created.addSource("First organisation","Second editorial reference","https://example.invalid/reference-a",TIME.minusSeconds(10));
        created.publish(TIME.plusSeconds(1));
        var id=guides.saveAndFlush(created).getId();
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            em.clear();var loaded=guides.findById(id).orElseThrow();
            assertThat(loaded.getSlug()).isEqualTo("offline-guide");assertThat(loaded.getCategory()).isEqualTo("example-category");
            assertThat(loaded.getContent()).isEqualTo(BODY);assertThat(loaded.getStatus()).isEqualTo(GuideStatus.PUBLISHED);
            assertThat(loaded.getPublishedAt()).isEqualTo(TIME.plusSeconds(1));assertThat(loaded.getUpdatedAt()).isEqualTo(TIME.plusSeconds(1));
            assertThat(loaded.getSources()).extracting(GuideSource::getTitle).containsExactly("First editorial reference","Second editorial reference");
            assertThat(loaded.getSources()).extracting(GuideSource::getAccessedAt).containsExactly(TIME.minusSeconds(20),TIME.minusSeconds(10));
            assertThatThrownBy(()->loaded.getSources().clear()).isInstanceOf(UnsupportedOperationException.class);
        });
    }

    @Test
    void slugUniquenessIsEnforcedByTheDatabase() {
        fixture("same-slug",null);
        assertThatThrownBy(()->fixture("same-slug",TIME)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(guides.count()).isEqualTo(1);
    }

    @Test
    void databaseRequiresCoherentPublicationAndOrderedSourceIdentity() {
        var guide=fixture("draft",null);
        assertThatThrownBy(()->jdbc.update("UPDATE guide SET status='PUBLISHED' WHERE id=?",guide.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE guide SET status='REVIEWING' WHERE id=?",guide.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE guide SET published_at=? WHERE id=?",java.sql.Timestamp.from(TIME),guide.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE guide SET status='PUBLISHED',published_at=?,updated_at=? WHERE id=?",
                java.sql.Timestamp.from(TIME),java.sql.Timestamp.from(TIME.minusSeconds(1)),guide.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO guide_source VALUES (gen_random_uuid(),?,'Org','Title','https://example.invalid',?,-1)",guide.getId(),java.sql.Timestamp.from(TIME)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO guide_source VALUES (gen_random_uuid(),?,'Org','Title','https://example.invalid',?,0)",guide.getId(),java.sql.Timestamp.from(TIME));
        assertThatThrownBy(()->jdbc.update("INSERT INTO guide_source VALUES (gen_random_uuid(),?,'Org','Other','https://example.invalid',?,0)",guide.getId(),java.sql.Timestamp.from(TIME)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void listReturnsOnlyPublishedMetadataInDeterministicNewestThenSlugOrder() throws Exception {
        fixture("zebra",TIME);fixture("alpha",TIME);fixture("newest",TIME.plusSeconds(1));fixture("private-draft",null);
        String before=snapshot();
        var response=get("/api/guides");assertThat(response.statusCode()).isEqualTo(200);
        var rows=json.readTree(response.body());assertThat(rows.size()).isEqualTo(3);
        assertThat(rows.get(0).get("slug").asString()).isEqualTo("newest");
        assertThat(rows.get(1).get("slug").asString()).isEqualTo("alpha");assertThat(rows.get(2).get("slug").asString()).isEqualTo("zebra");
        assertThat(rows.get(1)).isEqualTo(json.readTree("""
            {"slug":"alpha","category":"example-category","title":"  Offline title — 指南!  ","summary":" Offline summary ",
             "publishedAt":"2026-10-04T12:00:00.123456Z","updatedAt":"2026-10-04T12:00:00.123456Z"}
            """));
        assertThat(response.body()).doesNotContain("private-draft","content","Markdown","sources","status","\"id\"");
        assertThat(json.readTree(get("/api/guides").body())).isEqualTo(rows);assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void publishedDetailReturnsExactMarkdownAndReferencesWithoutFetchingTheirUrls() throws Exception {
        var requests=new AtomicInteger();
        var source=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        source.createContext("/",exchange->{requests.incrementAndGet();exchange.sendResponseHeaders(500,-1);exchange.close();});source.start();
        try {
            var guide=new Guide("published","example-category","Title","Summary",BODY,TIME);
            String url="http://127.0.0.1:"+source.getAddress().getPort()+"/reference";
            guide.addSource("Offline organisation — 官方","First source",url,TIME.minusSeconds(1));
            guide.addSource("Other organisation","Second source","https://example.invalid/other",TIME);
            guide.publish(TIME);guides.saveAndFlush(guide);
            String before=snapshot();
            var response=get("/api/guides/published");assertThat(response.statusCode()).isEqualTo(200);
            var body=json.readTree(response.body());
            assertThat(body.get("slug").asString()).isEqualTo("published");assertThat(body.get("category").asString()).isEqualTo("example-category");
            assertThat(body.get("content").asString()).isEqualTo(BODY);
            assertThat(body.get("publishedAt").asString()).isEqualTo(TIME.toString());assertThat(body.get("updatedAt").asString()).isEqualTo(TIME.toString());
            var references=body.get("sources");assertThat(references.size()).isEqualTo(2);
            assertThat(references.get(0).get("organisation").asString()).isEqualTo("Offline organisation — 官方");
            assertThat(references.get(0).get("title").asString()).isEqualTo("First source");assertThat(references.get(0).get("url").asString()).isEqualTo(url);
            assertThat(references.get(0).get("accessedAt").asString()).isEqualTo(TIME.minusSeconds(1).toString());
            assertThat(references.get(1).get("title").asString()).isEqualTo("Second source");
            assertThat(response.body()).doesNotContain("\"id\"","\"status\"","hibernateLazyInitializer");
            assertThat(json.readTree(get("/api/guides/published").body())).isEqualTo(body);
            get("/api/guides");assertThat(requests.get()).isZero();assertThat(snapshot()).isEqualTo(before);
        } finally {source.stop(0);}
    }

    @Test
    void unknownAndUnpublishedSlugsHaveIdenticalSafe404AndCannotBeOverriddenByQueryInput() throws Exception {
        var draft=fixture("private-draft",null);String before=snapshot();
        var missing=get("/api/guides/unknown");var hidden=get("/api/guides/private-draft?status=PUBLISHED");
        assertThat(missing.statusCode()).isEqualTo(404);assertThat(hidden.statusCode()).isEqualTo(404);
        assertThat(json.readTree(missing.body())).isEqualTo(json.readTree("{\"code\":\"GUIDE_NOT_FOUND\",\"message\":\"Guide not found.\"}"));
        assertThat(hidden.body()).isEqualTo(missing.body());assertThat(hidden.body()).doesNotContain(draft.getTitle(),draft.getSummary(),"Markdown");
        assertThat(json.readTree(get("/api/guides?status=DRAFT").body()).isEmpty()).isTrue();assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void emptyPublishedListAndPublishedGuideWithoutSourcesAreValid() throws Exception {
        assertThat(json.readTree(get("/api/guides").body()).isEmpty()).isTrue();
        fixture("no-sources",TIME);
        var response=get("/api/guides/no-sources");assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("sources").isEmpty()).isTrue();
    }

    @Test
    void unexpectedFailuresReturnBounded500WithoutDiagnosticsOrDraftContent() throws Exception {
        doThrow(new IllegalStateException("SECRET SQL /local/path draft body")).when(query).list();
        var response=get("/api/guides");assertThat(response.statusCode()).isEqualTo(500);
        assertThat(json.readTree(response.body())).isEqualTo(json.readTree("{\"code\":\"GUIDES_READ_FAILED\",\"message\":\"Guides could not be read.\"}"));
        doThrow(new IllegalStateException("SECRET SQL /local/path draft body")).when(query).detail("hidden");
        assertThat(get("/api/guides/hidden").body()).isEqualTo(response.body());
    }

    @ParameterizedTest
    @ValueSource(strings={"POST","PUT","PATCH","DELETE"})
    void noPublicWriteMethodsExist(String method) throws Exception {
        var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/guides"))
                .method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(405);verifyNoInteractions(query);
    }

    @Test
    void publicationIsExplicitAndDoesNotDependOnReadTime() {
        var draft=new Guide("draft","generic","Title","Summary",BODY,TIME);
        assertThat(draft.getStatus()).isEqualTo(GuideStatus.DRAFT);assertThat(draft.getPublishedAt()).isNull();
        assertThatIllegalArgumentException().isThrownBy(()->draft.publish(TIME.minusSeconds(1)));
        draft.publish(TIME);
        assertThatIllegalArgumentException().isThrownBy(()->draft.publish(TIME.plusSeconds(1)));
    }
}
