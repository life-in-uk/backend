package info.lifeinuk.backend.acquisition;

import static info.lifeinuk.backend.roads.RoadsCurrentStateProjector.Outcome.APPLIED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import info.lifeinuk.backend.acquisition.NationalHighwaysRoadClosuresAcquisitionTest.LocalClient;
import info.lifeinuk.backend.acquisition.NationalHighwaysRoadClosuresAcquisitionTest.SimulatedTime;
import info.lifeinuk.backend.evidence.AcquisitionHistory;
import info.lifeinuk.backend.roads.RoadsCurrentState;
import info.lifeinuk.backend.roads.RoadsCurrentStates;
import info.lifeinuk.backend.source.NationalHighwaysBootstrap;
import info.lifeinuk.backend.source.QualifiedSourceEndpoints;
import info.lifeinuk.backend.source.SourceEndpoint;
import info.lifeinuk.backend.support.IsolatedPostgres;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

/** Latest-only Roads refresh: replaces the one Current State row and never writes run or evidence history. */
@SpringBootTest(properties = {
    "spring.flyway.schemas=nh_latest_refresh_tests", "spring.flyway.default-schema=nh_latest_refresh_tests",
    "spring.jpa.properties.hibernate.default_schema=nh_latest_refresh_tests",
    "spring.datasource.hikari.schema=nh_latest_refresh_tests"
})
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
class NationalHighwaysRoadsLatestRefreshTest {
    static final Instant START = Instant.parse("2026-11-15T12:00:00Z");

    @Autowired NationalHighwaysRoadsLatestRefresh refresh;
    @Autowired RoadsCurrentStates states;
    @Autowired LocalClient client;
    @Autowired SimulatedTime time;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired NationalHighwaysBootstrap bootstrap;
    private HttpServer server;
    private ExecutorService executor;
    private volatile int status;
    private volatile byte[] firstPage;
    private volatile byte[] secondPage;

    /** A fixed clock the test can move forward, so successive refreshes have later snapshot times. */
    static final class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(START);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
        void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
    }

    @TestConfiguration
    static class Configuration {
        @Bean LocalClient localClient() { return new LocalClient(); }
        @Bean SimulatedTime simulatedTime() { return new SimulatedTime(); }
        @Bean MutableClock mutableClock() { return new MutableClock(); }
        @Bean @Primary NationalHighwaysRoadClosuresAcquisition offlineAcquisition(QualifiedSourceEndpoints endpoints,
                AcquisitionHistory history, LocalClient client, SimulatedTime time, MutableClock clock) {
            var http = new NationalHighwaysHttp(client, Duration.ofMillis(500), new NationalHighwaysRoadsProperties(
                    SourceEndpoint.NATIONAL_HIGHWAYS_ROADS_BASE_URL, NationalHighwaysRoadClosuresAcquisitionTest.FIXTURE_KEY));
            return new NationalHighwaysRoadClosuresAcquisition(endpoints, history, http, clock,
                    new RequestPacer(NationalHighwaysRoadClosuresAcquisition.MIN_REQUEST_SPACING, time::nanos, time::sleep));
        }
    }

    private byte[] fixture(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/nationalhighways/" + name + ".json")) {
            return input.readAllBytes();
        }
    }

    /** The parser-valid fixture with its situations renamed, so a later snapshot has different content. */
    private byte[] renamed() throws IOException {
        return new String(fixture("interpretation-page"), StandardCharsets.UTF_8)
                .replace("\"offline-situation\"", "\"later-situation\"")
                .replace("\"offline-group-situation\"", "\"later-group-situation\"")
                .getBytes(StandardCharsets.UTF_8);
    }

    @BeforeEach
    void prepare() throws IOException {
        jdbc.execute("TRUNCATE evidence_artifact, ingestion_run, roads_current_snapshot");
        bootstrap.initialize();
        jdbc.update("UPDATE source SET enabled=true WHERE source_key='national-highways'");
        jdbc.update("UPDATE source_endpoint SET enabled=true, qualification_status='QUALIFIED', qualification_record='Offline fixture approval', use_retention_policy=? WHERE endpoint_key='national-highways-road-closures'",
                SourceEndpoint.NATIONAL_HIGHWAYS_USE_RETENTION_POLICY);
        status = 200;
        firstPage = fixture("interpretation-page");
        secondPage = null;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try {
                boolean second = exchange.getRequestURI().getRawQuery().contains("pageCursor=");
                byte[] body = second ? secondPage : firstPage;
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                if (!second && secondPage != null) {
                    exchange.getResponseHeaders().add("x-next",
                            SourceEndpoint.NATIONAL_HIGHWAYS_ROAD_CLOSURES_URL + "?closureType=unplanned&pageCursor=c2");
                }
                exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        client.port = server.getAddress().getPort();
        client.requests.clear();
        client.failure = null;
        time.nanos.addAndGet(Duration.ofHours(1).toNanos());
        clock.advance(Duration.ofHours(1));
    }

    @AfterEach
    void cleanup() {
        server.stop(0);
        executor.shutdownNow();
        jdbc.execute("TRUNCATE evidence_artifact, ingestion_run, roads_current_snapshot");
        jdbc.update("UPDATE source_endpoint SET qualification_status='PENDING', qualification_record=NULL, enabled=true WHERE endpoint_key='national-highways-road-closures'");
    }

    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }

    private void assertNoHistory() {
        assertThat(count("ingestion_run")).isZero();
        assertThat(count("evidence_artifact")).isZero();
    }

    @Test
    void successStoresTheCompleteSnapshotWithoutHistory() {
        assertThat(refresh.refresh().outcome()).isEqualTo(APPLIED);
        RoadsCurrentState state = states.current().orElseThrow();
        assertThat(state.snapshotAt()).isEqualTo(clock.instant());
        assertThat(state.pageCount()).isEqualTo(1);
        assertThat(state.closures()).extracting(RoadsCurrentState.Record::situationId)
                .containsExactly("offline-situation", "offline-situation", "offline-group-situation");
        assertThat(count("roads_current_snapshot")).isEqualTo(1);
        assertNoHistory();
        assertThat(client.requests).hasSize(1);
    }

    @Test
    void repeatedRefreshesReplaceTheOneRowAndGrowNoTable() throws IOException {
        refresh.refresh();
        firstPage = renamed();
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofMinutes(10));
            assertThat(refresh.refresh().outcome()).isEqualTo(APPLIED);
        }
        var state = states.current().orElseThrow();
        assertThat(state.snapshotAt()).isEqualTo(clock.instant());
        assertThat(state.closures()).extracting(RoadsCurrentState.Record::situationId)
                .containsExactly("later-situation", "later-situation", "later-group-situation");
        assertThat(count("roads_current_snapshot")).isEqualTo(1);
        assertNoHistory();
    }

    @Test
    void everyPageMustParseAndFailuresKeepThePreviousSnapshotAndTime() throws IOException {
        refresh.refresh();
        var before = states.current().orElseThrow();
        clock.advance(Duration.ofMinutes(10));
        status = 503;
        assertThat(refresh.refresh().failureCode()).isEqualTo("HTTP_STATUS");
        status = 429;
        assertThat(refresh.refresh().rateLimited()).isTrue();
        status = 200;
        firstPage = "{not json".getBytes(StandardCharsets.UTF_8);
        assertThat(refresh.refresh().failureCode()).isEqualTo("INVALID_RESPONSE");
        firstPage = "{\"D2Payload\":{\"feedType\":\"MeasuredDataPublication\"}}".getBytes(StandardCharsets.UTF_8);
        assertThat(refresh.refresh().failureCode()).isEqualTo("INVALID_RESPONSE");
        // Records missing required closure fields are rejected, never stored.
        firstPage = fixture("unplanned-page");
        assertThat(refresh.refresh().failureCode()).isEqualTo("INVALID_RESPONSE");
        // A valid first page with an invalid second page replaces nothing.
        firstPage = renamed();
        secondPage = "{\"D2Payload\":{}}".getBytes(StandardCharsets.UTF_8);
        assertThat(refresh.refresh().failureCode()).isEqualTo("INVALID_RESPONSE");
        var after = states.current().orElseThrow();
        assertThat(after.snapshotAt()).isEqualTo(before.snapshotAt());
        assertThat(after.ingestionRunId()).isEqualTo(before.ingestionRunId());
        assertThat(after.closures()).isEqualTo(before.closures());
        assertNoHistory();
    }

    @Test
    void twoValidPagesFormOneSnapshot() throws IOException {
        firstPage = fixture("interpretation-page");
        secondPage = renamed();
        assertThat(refresh.refresh().outcome()).isEqualTo(APPLIED);
        var state = states.current().orElseThrow();
        assertThat(state.pageCount()).isEqualTo(2);
        assertThat(state.closures()).extracting(RoadsCurrentState.Record::situationId).containsExactly(
                "offline-situation", "offline-situation", "offline-group-situation",
                "later-situation", "later-situation", "later-group-situation");
        assertThat(time.sleeps).isNotEmpty();
        assertNoHistory();
    }

    @Test
    void anIneligibleEndpointMakesNoRequestAndChangesNothing() {
        refresh.refresh();
        var before = states.current().orElseThrow();
        jdbc.update("UPDATE source_endpoint SET enabled=false WHERE endpoint_key='national-highways-road-closures'");
        assertThatThrownBy(() -> refresh.refresh()).isInstanceOf(RuntimeException.class);
        assertThat(client.requests).hasSize(1);
        assertThat(states.current().orElseThrow()).isEqualTo(before);
        assertNoHistory();
    }
}
