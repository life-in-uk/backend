package info.lifeinuk.backend.marketindices;

import info.lifeinuk.backend.support.IsolatedPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ContextConfiguration;
import static org.assertj.core.api.Assertions.assertThat;

/** With the shipped defaults: ingestion answers 503, public read answers 404, and nothing is stored. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.hikari.maximum-pool-size=2") // own context: small pool, closed afterwards
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
@org.springframework.test.annotation.DirtiesContext
class MarketIndexDefaultsApiTest {
    @LocalServerPort int port;
    @Autowired MarketIndexProperties properties;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void everythingIsOffByDefault() throws Exception {
        assertThat(properties.collectorKeys()).isEmpty();
        assertThat(properties.acceptedSources()).isEmpty();
        assertThat(properties.publicEnabled()).isFalse();
        assertThat(properties.maxBackfill()).isEqualTo(Duration.ofDays(30));
        assertThat(properties.retention()).isEqualTo(Duration.ofDays(730));

        for (String path : new String[] {MarketIndexController.OBSERVATIONS, MarketIndexController.HEARTBEAT}) {
            var post = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("Content-Type", "application/json").header(MarketIndexIngestSecurity.HEADER, "k1." + "x".repeat(40))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(post.statusCode()).isEqualTo(503);
            assertThat(post.body()).contains("MARKET_INGEST_DISABLED");
            assertThat(post.headers().firstValue("Cache-Control")).hasValue("no-store");
        }
        var get = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + MarketIndexController.LATEST)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(get.statusCode()).isEqualTo(404);
        assertThat(get.body()).contains("MARKET_INDICES_DISABLED");
        assertThat(get.headers().firstValue("Cache-Control")).hasValue("no-store");
    }
}
