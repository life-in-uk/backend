package info.lifeinuk.backend.guides;

import com.sun.net.httpserver.HttpServer;
import info.lifeinuk.backend.LifeInUkBackendApplication;
import info.lifeinuk.backend.support.IsolatedPostgres;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static info.lifeinuk.backend.guides.GuideImporter.Outcome.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.flyway.schemas=guide_import_tests", "spring.flyway.default-schema=guide_import_tests",
    "spring.jpa.properties.hibernate.default_schema=guide_import_tests", "spring.datasource.hikari.schema=guide_import_tests",
    // A similarly named property cannot opt ordinary startup into an import.
    "import-guide=/must-not-be-read-by-normal-startup.json"
})
@ContextConfiguration(initializers=IsolatedPostgres.Initializer.class)
class GuideImporterTest {
    @Autowired GuideImporter importer;
    @Autowired GuideImportReader reader;
    @Autowired GuideImportCommand command;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    private final JsonMapper json=JsonMapper.builder().build();
    private final HttpClient http=HttpClient.newHttpClient();

    @BeforeEach
    void clear() {
        jdbc.execute("DROP TRIGGER IF EXISTS guide_import_fail ON guide_source");
        jdbc.execute("DROP FUNCTION IF EXISTS guide_import_fail()");
        jdbc.execute("TRUNCATE guide_source,guide");
    }
    GuideImportDefinition fixture() throws Exception {
        return reader.parse(GuideImportReaderTest.fixture().getBytes(StandardCharsets.UTF_8));
    }
    GuideImportDefinition changed(GuideImportDefinition original, GuideStatus status, Instant published,
            String title,List<GuideImportDefinition.Source> sources) {
        return new GuideImportDefinition(original.slug(),original.category(),title,original.summary(),original.content(),
                status,published,original.updatedAt().plusSeconds(1),sources);
    }
    String snapshot() {
        return jdbc.queryForObject("""
            SELECT json_build_object('guides',(SELECT json_agg(row_to_json(g) ORDER BY id) FROM guide g),
            'sources',(SELECT json_agg(row_to_json(s) ORDER BY guide_id,source_order) FROM guide_source s))::text
            """,String.class);
    }
    HttpResponse<String> get(String suffix) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/guides"+suffix))
                .timeout(java.time.Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void newGuideAndSourcesPersistAndIdenticalReplayChangesNoRowOrIdentity() throws Exception {
        var input=fixture();var created=importer.apply(input);assertThat(created.outcome()).isEqualTo(CREATED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_source",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT content FROM guide",String.class)).isEqualTo(input.content());
        assertThat(jdbc.queryForList("SELECT title FROM guide_source ORDER BY source_order",String.class))
                .containsExactly("First editorial reference","Second editorial reference");
        String before=snapshot();var replay=importer.apply(input);
        assertThat(replay.outcome()).isEqualTo(UNCHANGED);assertThat(replay.guideId()).isEqualTo(created.guideId());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void updateRetainsGuideIdentityAndReplacesSourcesExactlyIncludingRemovalAndOrder() throws Exception {
        var input=fixture();var created=importer.apply(input);
        var a=input.sources().getFirst();var b=input.sources().get(1);
        var c=new GuideImportDefinition.Source("C","Third","https://example.invalid/c",Instant.parse("2026-10-02T12:00:00Z"));
        var d=new GuideImportDefinition.Source("D","Fourth","https://example.invalid/d",Instant.parse("2026-10-02T12:00:00Z"));
        var abc=changed(input,GuideStatus.DRAFT,null,"A B C",List.of(a,b,c));importer.apply(abc);
        var acd=changed(input,GuideStatus.DRAFT,null,"A C D — updated",List.of(a,c,d));
        var result=importer.apply(acd);assertThat(result.outcome()).isEqualTo(UPDATED);assertThat(result.guideId()).isEqualTo(created.guideId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT title FROM guide",String.class)).isEqualTo("A C D — updated");
        assertThat(jdbc.queryForList("SELECT title FROM guide_source ORDER BY source_order",String.class))
                .containsExactly(a.title(),c.title(),d.title());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_source WHERE title=?",Integer.class,b.title())).isZero();
        String before=snapshot();assertThat(importer.apply(acd).outcome()).isEqualTo(UNCHANGED);assertThat(snapshot()).isEqualTo(before);
        // Reordering is meaningful, never sorted by URL/title.
        importer.apply(changed(acd,GuideStatus.DRAFT,null,"Reordered",List.of(d,a,c)));
        assertThat(jdbc.queryForList("SELECT title FROM guide_source ORDER BY source_order",String.class)).containsExactly(d.title(),a.title(),c.title());
        importer.apply(changed(acd,GuideStatus.DRAFT,null,"No sources",List.of()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_source",Integer.class)).isZero();
    }

    @Test
    void metadataOnlyUpdateKeepsUnchangedSourceRows() throws Exception {
        var input=fixture();importer.apply(input);
        var ids=jdbc.queryForList("SELECT id FROM guide_source ORDER BY source_order");
        importer.apply(changed(input,GuideStatus.DRAFT,null,"Updated only title",input.sources()));
        assertThat(jdbc.queryForList("SELECT id FROM guide_source ORDER BY source_order")).isEqualTo(ids);
    }

    @Test
    void draftPublishedAndExplicitReturnToDraftUseUnchangedPublicApiSemantics() throws Exception {
        var draft=fixture();importer.apply(draft);
        assertThat(get("/"+draft.slug()).statusCode()).isEqualTo(404);assertThat(json.readTree(get("").body()).isEmpty()).isTrue();
        var published=changed(draft,GuideStatus.PUBLISHED,draft.updatedAt(),"Reviewed synthetic content",draft.sources());
        assertThat(importer.apply(published).outcome()).isEqualTo(UPDATED);
        var response=get("/"+draft.slug());assertThat(response.statusCode()).isEqualTo(200);
        var body=json.readTree(response.body());assertThat(body.get("content").asString()).isEqualTo(draft.content());
        assertThat(body.get("publishedAt").asString()).isEqualTo(draft.updatedAt().toString());
        assertThat(body.get("updatedAt").asString()).isEqualTo(published.updatedAt().toString());
        assertThat(body.get("sources").size()).isEqualTo(2);assertThat(json.readTree(get("").body()).size()).isEqualTo(1);
        importer.apply(draft); // Status is supplied, never retained/promoted by the importer.
        assertThat(get("/"+draft.slug()).statusCode()).isEqualTo(404);
    }

    @Test
    void invalidCompleteInputLeavesExistingGuideAndSourcesUntouched() throws Exception {
        var input=fixture();importer.apply(input);String before=snapshot();
        String malformed=GuideImportReaderTest.fixture().replace("https://example.invalid/reference-a"," ");
        assertThatIllegalArgumentException().isThrownBy(()->importer.apply(reader.parse(malformed.getBytes(StandardCharsets.UTF_8))));
        assertThatIllegalArgumentException().isThrownBy(()->new GuideImportDefinition(input.slug(),input.category(),input.title(),
                input.summary(),input.content(),GuideStatus.PUBLISHED,null,input.updatedAt(),input.sources()));
        assertThatIllegalArgumentException().isThrownBy(()->new GuideImportDefinition(input.slug(),input.category(),input.title(),
                input.summary(),input.content(),GuideStatus.DRAFT,input.updatedAt(),input.updatedAt(),input.sources()));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void databaseFailureAfterGuideUpdateAndSourceDeletionRollsBackEntireExistingAggregate() throws Exception {
        var input=fixture();importer.apply(input);String before=snapshot();
        rejectSourceInserts();
        var replacement=changed(input,GuideStatus.PUBLISHED,input.updatedAt(),"Should roll back",List.of(
                new GuideImportDefinition.Source("Replacement","Reject","https://example.invalid/new",input.updatedAt())));
        assertThatThrownBy(()->importer.apply(replacement)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(snapshot()).isEqualTo(before);assertThat(get("/"+input.slug()).statusCode()).isEqualTo(404);
    }

    @Test
    void databaseFailureOnNewGuideLeavesNeitherGuideNorSources() throws Exception {
        rejectSourceInserts();
        assertThatThrownBy(()->importer.apply(fixture())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_source",Integer.class)).isZero();
    }
    void rejectSourceInserts() {
        jdbc.execute("CREATE FUNCTION guide_import_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Offline source failure'; END $$");
        jdbc.execute("CREATE TRIGGER guide_import_fail BEFORE INSERT ON guide_source FOR EACH ROW EXECUTE FUNCTION guide_import_fail()");
    }

    @Test
    void actualCliCommandImportsOneLocalFixtureAndNeverFetchesReferenceUrls() throws Exception {
        var calls=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{calls.incrementAndGet();exchange.sendResponseHeaders(500,-1);exchange.close();});server.start();
        Path file=java.nio.file.Files.createTempFile("guide-import-offline-",".json");
        try {
            var contents=GuideImportReaderTest.fixture().replace("https://example.invalid/reference-b","http://127.0.0.1:"+server.getAddress().getPort()+"/reference");
            java.nio.file.Files.writeString(file,contents,StandardCharsets.UTF_8);
            command.run(new DefaultApplicationArguments("--import-guide="+file));
            String before=snapshot();command.run(new DefaultApplicationArguments("--import-guide="+file));
            assertThat(snapshot()).isEqualTo(before);assertThat(calls.get()).isZero();
        } finally {server.stop(0);java.nio.file.Files.deleteIfExists(file);}
    }

    @Test
    void normalApplicationRestartWithAnImportPropertyDoesNotTouchExistingGuidesOrReadFiles() throws Exception {
        importer.apply(fixture());String before=snapshot();
        try(var restarted=new SpringApplicationBuilder(LifeInUkBackendApplication.class).web(WebApplicationType.NONE)
                .initializers(new IsolatedPostgres.Initializer()).properties(
                        "spring.flyway.schemas=guide_import_tests","spring.flyway.default-schema=guide_import_tests",
                        "spring.jpa.properties.hibernate.default_schema=guide_import_tests","spring.datasource.hikari.schema=guide_import_tests",
                        "import-guide=/must-not-be-read-by-normal-startup.json").run()) {
            assertThat(restarted.getBean(GuideQuery.class).list()).isEmpty();
        }
        assertThat(snapshot()).isEqualTo(before);
    }
}
