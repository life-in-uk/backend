package info.lifeinuk.backend.rail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import info.lifeinuk.backend.support.IsolatedPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The backend Rail flag gates every RDM call, independently of whether credentials are present. */
class RailAccessTest {
    private static final byte[] BOARD = "{\"locationName\":\"Sheffield\",\"crs\":\"SHF\",\"generatedAt\":\"2026-11-11T10:00:00Z\"}"
            .getBytes(StandardCharsets.UTF_8);
    private static final List<String> PATHS = List.of("/api/rail/stations/SHF/arrivals",
            "/api/rail/stations/SHF/disruptions", "/api/rail/stations/SHF/disruptions?direction=departures");

    static HttpResponse<String> get(int port, String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void flagIsOffByDefault() {
        assertThat(new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(java.util.Map.of()))
                .bindOrCreate("life-in-uk.rail-access", RailAccess.class).enabled()).isFalse();
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.schemas=rail_access_off_tests", "spring.flyway.default-schema=rail_access_off_tests",
        "spring.jpa.properties.hibernate.default_schema=rail_access_off_tests",
        "spring.datasource.hikari.schema=rail_access_off_tests",
        // Credentials present by accident; the flag is left at its default.
        "life-in-uk.rail.api-key=offline-arrivals-key",
        "life-in-uk.rail-departures.base-url=https://api1.raildata.org.uk/1010-live-departure-board-dep1_2/LDBWS",
        "life-in-uk.rail-departures.api-key=offline-departures-key"
    })
    @ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
    class DisabledWithCredentials {
        @LocalServerPort int port;
        @Autowired RailAccess access;
        @MockitoBean RailClient client;

        @Test
        void everyRailEndpointRefusesWithoutContactingRdm() throws Exception {
            assertThat(access.enabled()).isFalse();
            for (String path : PATHS) {
                var response = get(port, path);
                assertThat(response.statusCode()).as(path).isEqualTo(503);
                assertThat(response.body()).contains("\"code\":\"RAIL_DISABLED\"");
                assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
            }
            // Request validation still answers first; nothing reaches the provider either way.
            assertThat(get(port, "/api/rail/stations/S1/arrivals").statusCode()).isEqualTo(400);
            verifyNoInteractions(client);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.schemas=rail_access_on_tests", "spring.flyway.default-schema=rail_access_on_tests",
        "spring.jpa.properties.hibernate.default_schema=rail_access_on_tests",
        "spring.datasource.hikari.schema=rail_access_on_tests",
        "life-in-uk.rail.api-key=offline-arrivals-key",
        "life-in-uk.rail-access.enabled=true"
    })
    @ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
    class ExplicitlyEnabled {
        @LocalServerPort int port;
        @MockitoBean RailClient client;

        @Test
        void onlyAnExplicitFlagLetsRequestsReachTheProviderClient() throws Exception {
            when(client.arrivals(anyString(), any(), any())).thenReturn(BOARD);
            var response = get(port, "/api/rail/stations/SHF/disruptions");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"crs\":\"SHF\"");
            verify(client).arrivals("SHF", 9, null);
        }
    }
}
