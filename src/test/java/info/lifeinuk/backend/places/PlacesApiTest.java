package info.lifeinuk.backend.places;

import info.lifeinuk.backend.support.IsolatedPostgres;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Public HTTP contract with the OS Names boundary mocked: no live provider access. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
class PlacesApiTest {
    static final String FIXTURE_KEY = "offline-fixture-os-names-key-91aa";
    @LocalServerPort int port;
    @MockitoBean OsNamesClient client;
    @Autowired OsNamesProperties properties;
    @Autowired JdbcTemplate jdbc;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void reset() { org.mockito.Mockito.reset(client); }

    HttpResponse<String> get(String rawQuery) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/places/search" + rawQuery))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    static String q(String value) { return "?q=" + URLEncoder.encode(value, StandardCharsets.UTF_8); }

    @Test
    void successfulSearchReturnsOnlyTheNormalisedContractAndIsNotCacheable() throws Exception {
        when(client.find("Sheffield")).thenReturn(PlaceSearchTest.page(1, PlaceSearchTest.entry("osgb4000000074564391",
                "Sheffield", "City", 435_000, 387_000, ",\"REGION\":\"Yorkshire and the Humber\",\"COUNTRY\":\"England\""))
                .getBytes(StandardCharsets.UTF_8));
        var response = get(q(" Sheffield "));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        var body = json.readTree(response.body());
        assertThat(body.get("query").asString()).isEqualTo("Sheffield");
        assertThat(body.get("attribution").asString()).startsWith("Contains OS data © Crown copyright and database right ");
        var place = body.get("places").get(0);
        assertThat(place.properties().stream().map(Map.Entry::getKey).toList())
                .containsExactly("id", "label", "name", "type", "area", "region", "country", "latitude", "longitude");
        assertThat(place.get("latitude").asDouble()).isBetween(53.3, 53.5);
        assertThat(place.get("longitude").asDouble()).isBetween(-1.6, -1.3);
        assertThat(response.body()).doesNotContain("GEOMETRY", "GAZETTEER_ENTRY", "NAMES_URI", "MBR_", "435000", "header");
        verify(client, times(1)).find("Sheffield");
    }

    @Test
    void emptyProviderResultIsASuccessfulEmptyList() throws Exception {
        when(client.find(anyString())).thenReturn("{\"header\":{\"totalresults\":0}}".getBytes(StandardCharsets.UTF_8));
        var response = get(q("Qwxzzy"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("places").size()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "?q=", "?q=%20%20", "?q=%09", "?other=Sheffield"})
    void missingBlankOrControlQueryIsABoundedClientError(String rawQuery) throws Exception {
        var response = get(rawQuery);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("PLACE_QUERY_INVALID");
        verifyNoInteractions(client);
    }

    @Test
    void oversizedQueryIsABoundedClientError() throws Exception {
        var response = get(q("a".repeat(PlaceSearch.MAX_QUERY_LENGTH + 1)));
        assertThat(response.statusCode()).isEqualTo(400);
        verifyNoInteractions(client);
    }

    @Test
    void upstreamFailuresMapToBoundedErrorsWithoutLeakingCredentialsOrQueries() throws Exception {
        record Case(PlaceSearchFailure.Reason reason, int status, String code) { }
        for (var c : new Case[]{new Case(PlaceSearchFailure.Reason.NOT_CONFIGURED, 503, "PLACES_NOT_CONFIGURED"),
                new Case(PlaceSearchFailure.Reason.UNAVAILABLE, 502, "PLACES_UNAVAILABLE"),
                new Case(PlaceSearchFailure.Reason.INVALID_RESPONSE, 502, "PLACES_UPSTREAM_INVALID")}) {
            org.mockito.Mockito.reset(client);
            when(client.find(anyString())).thenThrow(new PlaceSearchFailure(c.reason()));
            var response = get(q("Private School Lane"));
            assertThat(response.statusCode()).isEqualTo(c.status());
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            var body = json.readTree(response.body());
            assertThat(body.get("code").asString()).isEqualTo(c.code());
            assertThat(body.properties()).hasSize(2);
            assertThat(response.body()).doesNotContain(FIXTURE_KEY, "Private School Lane", "os.uk", "Exception");
        }
        org.mockito.Mockito.reset(client);
        when(client.find(anyString())).thenThrow(new IllegalStateException("PRIVATE " + FIXTURE_KEY));
        var unexpected = get(q("Leeds"));
        assertThat(unexpected.statusCode()).isEqualTo(500);
        assertThat(unexpected.body()).doesNotContain(FIXTURE_KEY, "PRIVATE");
    }

    @Test
    void testsNeverReceiveTheRealKeyAndSearchesAreNotPersisted() {
        assertThat(properties.apiKey()).isEmpty();
        assertThat(properties.baseUrl()).isEqualTo(OsNamesProperties.CANONICAL_BASE_URL);
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=current_schema()", String.class))
                .noneMatch(table -> table.contains("place") || table.contains("search"));
    }

    @Test
    void productionConfigurationBindsTheOsNamesEnvironmentVariables() throws Exception {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("simulatedEnvironment",
                Map.of("OS_NAMES_API_KEY", FIXTURE_KEY, "OS_NAMES_BASE_URL", "https://api.os.uk/search/names/v1")));
        new YamlPropertySourceLoader().load("production", new FileSystemResource("src/main/resources/application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        ConfigurationPropertySources.attach(environment);
        var bound = Binder.get(environment).bindOrCreate("life-in-uk.places.os-names", Bindable.of(OsNamesProperties.class));
        assertThat(bound.apiKey()).isEqualTo(FIXTURE_KEY);
        assertThat(bound.hasUsableApiKey()).isTrue();
        assertThat(bound.baseUrl()).isEqualTo(OsNamesProperties.CANONICAL_BASE_URL);
        assertThat(bound.toString()).doesNotContain(FIXTURE_KEY);
    }
}
