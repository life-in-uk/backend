package info.lifeinuk.backend.rail;

import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RailIntegrationTest {
    private static final String KEY = "offline-test-key";
    private static final String BOARD = """
        {"locationName":"Sheffield","crs":"SHF","generatedAt":"2026-10-27T13:45:27Z",
         "trainServices":[{"sta":"13:28","eta":"13:43","platform":"2","operator":"Railway","isCancelled":false,"delayReason":"Late train",
           "origin":[{"locationName":"Reading","crs":"RDG"}],"destination":[{"locationName":"Leeds","crs":"LDS"}],
           "previousCallingPoints":[{"callingPoint":[{"locationName":"Derby","crs":"DBY","st":"13:00","at":"13:05"}]}]},
          {"sta":"13:35","eta":"Cancelled","isCancelled":true,"cancelReason":"Disruption"}]}
        """;
    @SuppressWarnings("unchecked")
    private HttpClient upstream(int status, String body) {
        HttpClient client=mock(HttpClient.class);
        when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        when(client.<byte[]>sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(inv -> {
            HttpResponse.BodyHandler<byte[]> handler=inv.getArgument(1);
            HttpResponse.ResponseInfo info=mock(HttpResponse.ResponseInfo.class);
            when(info.statusCode()).thenReturn(status);
            when(info.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type",List.of("application/json")),(a,b)->true));
            try {
                var subscriber=handler.apply(info);
                subscriber.onSubscribe(new Flow.Subscription() { public void request(long n) {} public void cancel() {} });
                subscriber.onNext(List.of(ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8))));
                subscriber.onComplete();
                HttpResponse<byte[]> response=mock(HttpResponse.class);
                when(response.body()).thenReturn(subscriber.getBody().toCompletableFuture().join());
                return CompletableFuture.completedFuture(response);
            } catch (Exception e) { return CompletableFuture.failedFuture(e); }
        });
        return client;
    }
    private RailClient client(HttpClient http, String key) { return new RailClient(new RailProperties(RailProperties.DEFAULT_BASE,key),http,Duration.ofMillis(100)); }
    @Test void parsesBoardAndBuildsAuthenticatedRequest() {
        HttpClient http=upstream(200,BOARD);
        var result=new RailService(client(http,KEY)).arrivals("shf",9,60);
        assertThat(result.stationName()).isEqualTo("Sheffield");
        assertThat(result.crs()).isEqualTo("SHF");
        assertThat(result.generatedAt()).isEqualTo("2026-10-27T13:45:27Z");
        assertThat(result.services()).hasSize(2);
        var delayed=result.services().getFirst();
        assertThat(delayed.estimatedArrival()).isEqualTo("13:43");
        assertThat(delayed.delayReason()).isEqualTo("Late train");
        assertThat(delayed.origin().getFirst().name()).isEqualTo("Reading");
        assertThat(delayed.previousCallingPoints().getFirst().callingPoints().getFirst().name()).isEqualTo("Derby");
        assertThat(result.services().get(1).cancelled()).isTrue();
        assertThat(result.services().get(1).cancellationReason()).isEqualTo("Disruption");
        var captor=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).sendAsync(captor.capture(),any());
        assertThat(captor.getValue().uri().toString()).isEqualTo(RailProperties.DEFAULT_BASE+"/api/20220120/GetArrBoardWithDetails/SHF?numRows=9&timeWindow=60");
        assertThat(captor.getValue().headers().firstValue("x-apikey")).contains(KEY);
        assertThat(captor.getValue().uri().toString()).doesNotContain(KEY);
    }
    @ParameterizedTest @ValueSource(strings={"", "SH", "SHFF", "S1F", " shf", "éab"})
    void invalidCrs(String crs) { assertThatIllegalArgumentException().isThrownBy(() -> RailService.validate(crs,null,null)); }
    @Test void bounds() {
        assertThatIllegalArgumentException().isThrownBy(() -> RailService.validate("SHF",10,null));
        assertThatIllegalArgumentException().isThrownBy(() -> RailService.validate("SHF",0,null));
        assertThatIllegalArgumentException().isThrownBy(() -> RailService.validate("SHF",null,120));
        assertThatIllegalArgumentException().isThrownBy(() -> RailService.validate("SHF",null,-120));
        assertThat(RailService.validate("shf",1,-119)).isEqualTo("SHF");
        assertThat(RailService.validate("SHF",9,119)).isEqualTo("SHF");
        assertThat(RailService.validate("SHF",null,0)).isEqualTo("SHF");
    }
    @Test void missingAndEmptyFields() {
        var service=new RailService(client(upstream(200,"{\"crs\":\"SHF\",\"locationName\":\"Sheffield\",\"trainServices\":[{}]}"),KEY)).arrivals("SHF",null,null).services().getFirst();
        assertThat(service.cancelled()).isNull(); assertThat(service.platform()).isNull(); assertThat(service.origin()).isNull();
        assertThat(new RailService(client(upstream(200,"{\"crs\":\"SHF\",\"locationName\":\"Sheffield\",\"trainServices\":[]}"),KEY)).arrivals("SHF",null,null).services()).isEmpty();
        assertThat(new RailService(client(upstream(200,"{\"crs\":\"SHF\",\"locationName\":\"Sheffield\"}"),KEY)).arrivals("SHF",null,null).services()).isEmpty();
    }
    @ParameterizedTest @ValueSource(ints={400,401,403,429,500,503}) void upstreamStatuses(int status) {
        var expected=status==400 ? RailFailure.Reason.UPSTREAM_REQUEST : status==401 || status==403 ? RailFailure.Reason.AUTHENTICATION : status==429 ? RailFailure.Reason.RATE_LIMIT : RailFailure.Reason.UPSTREAM_ERROR;
        assertThatThrownBy(() -> client(upstream(status,KEY),KEY).arrivals("SHF",null,null)).isInstanceOfSatisfying(RailFailure.class,e -> { assertThat(e.reason).isEqualTo(expected); assertThat(e.getMessage()).doesNotContain(KEY); });
    }
    @Test void timeoutAndMissingConfiguration() {
        HttpClient http=mock(HttpClient.class); when(http.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        when(http.<byte[]>sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(new CompletableFuture<>());
        assertThatThrownBy(() -> client(http,KEY).arrivals("SHF",null,null)).isInstanceOfSatisfying(RailFailure.class,e -> assertThat(e.reason).isEqualTo(RailFailure.Reason.TIMEOUT));
        assertThatThrownBy(() -> client(http,"").arrivals("SHF",null,null)).isInstanceOfSatisfying(RailFailure.class,e -> assertThat(e.reason).isEqualTo(RailFailure.Reason.NOT_CONFIGURED));
    }
    @Test void controllerValidatesAndMapsErrors() {
        var controller=new RailController(new RailService(client(upstream(403,KEY),KEY)));
        assertThat(controller.arrivals("bad!",null,null).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.arrivals("shf","abc",null).getStatusCode().value()).isEqualTo(400);
        var failure=catchThrowableOfType(RailFailure.class,() -> controller.arrivals("shf",null,null));
        var response=controller.upstream(failure);
        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody().toString()).doesNotContain(KEY);
    }
    @Test void httpControllerRouteHandlesSuccessAndBadInput() throws Exception {
        var controller = new RailController(new RailService(client(upstream(200, BOARD), KEY)));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/rail/stations/shf/arrivals").param("numRows", "3"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.stationName").value("Sheffield"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.services[0].estimatedArrival").value("13:43"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/rail/stations/SH1/arrivals"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    }
    @Test void httpControllerNeverReturnsUpstreamErrorBodyOrKey() throws Exception {
        var controller = new RailController(new RailService(client(upstream(403, "<html>private " + KEY + "</html>"), KEY)));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/rail/stations/SHF/arrivals"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadGateway())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("RAIL_UPSTREAM_AUTHENTICATION"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(KEY, "private", "<html>");
    }
    @Test void departuresUseSeparateConfigurationAndParseDepartureFields() {
        HttpClient http = upstream(200, "{\"crs\":\"SHF\",\"locationName\":\"Sheffield\",\"trainServices\":[{\"std\":\"23:55\",\"etd\":\"00:10\",\"destination\":[{\"locationName\":\"Leeds\"}]}]}");
        var departureConfig = new RailDepartureProperties("https://example.invalid/LDBWS", "offline-departure-key");
        var rail = new RailService(new RailClient(new RailProperties(RailProperties.DEFAULT_BASE, KEY), departureConfig, http, Duration.ofMillis(100)));
        var board = RailDisruptions.from(rail.board("shf", null, null, true), "departures");
        assertThat(board.disruptions()).hasSize(1);
        assertThat(board.disruptions().getFirst().delayMinutes()).isEqualTo(15);
        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).sendAsync(captor.capture(), any());
        assertThat(captor.getValue().uri().toString()).isEqualTo("https://example.invalid/LDBWS/api/20220120/GetDepBoardWithDetails/SHF");
        assertThat(captor.getValue().headers().firstValue("x-apikey")).contains("offline-departure-key");
    }
    @Test void disruptionsControllerDefaultsToArrivalsAndDeparturesReturnSanitizedUnavailable() throws Exception {
        var controller = new RailController(new RailService(client(upstream(200, BOARD), KEY)));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/rail/stations/SHF/disruptions").param("direction", "departures"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("RAIL_NOT_CONFIGURED"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/rail/stations/shf/disruptions"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.disruptions[0].delayMinutes").value(15));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/rail/stations/SHF/disruptions").param("direction", "bad"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    }
    @Test void departureParsingAndFilteringPreservesDisruptions() {
        String response = """
            {"crs":"SHF","locationName":"Sheffield","trainServices":[
              {"std":"10:00","etd":"10:09"},
              {"std":"10:00","etd":"10:10","destination":[{"locationName":"Leeds","crs":"LDS"}]},
              {"std":"10:00","etd":"10:15"},
              {"std":"10:00","etd":"Cancelled","isCancelled":true},
              {"std":"10:00","etd":"On time","isCancelled":false,"subsequentCallingPoints":[{"callingPoint":[{"locationName":"Derby","crs":"DBY","isCancelled":true,"cancelReason":"Disruption"}]}]},
              {"std":"10:00","etd":"Delayed"}, {"std":"10:00","etd":null}]}
            """;
        var config = new RailDepartureProperties("https://example.invalid/LDBWS", "offline-departure-key");
        var rail = new RailService(new RailClient(new RailProperties(RailProperties.DEFAULT_BASE, KEY), config, upstream(200, response), Duration.ofMillis(100)));
        var board = RailDisruptions.from(rail.board("SHF", 9, null, true), "departures");
        assertThat(board.unknownTimingCount()).isEqualTo(1);
        assertThat(board.disruptions()).hasSize(5);
        assertThat(board.disruptions().get(0).delayMinutes()).isEqualTo(10);
        assertThat(board.disruptions().get(0).checkDelayRepay()).isFalse();
        assertThat(board.disruptions().get(0).locations().getFirst().name()).isEqualTo("Leeds");
        assertThat(board.disruptions().get(1).delayMinutes()).isEqualTo(15);
        assertThat(board.disruptions().get(1).checkDelayRepay()).isTrue();
        assertThat(board.disruptions().get(2).status()).isEqualTo("cancelled");
        assertThat(board.disruptions().get(3).status()).isEqualTo("partially_cancelled");
        assertThat(board.disruptions().get(3).affectedStops().getFirst().name()).isEqualTo("Derby");
        assertThat(board.disruptions().get(4).status()).isEqualTo("delayed");
        assertThat(board.disruptions().get(4).delayMinutes()).isNull();
    }
    @ParameterizedTest @ValueSource(ints={401,403,429,500}) void departureErrorsStaySanitized(int status) {
        var config = new RailDepartureProperties("https://example.invalid/LDBWS", "offline-departure-key");
        var rail = new RailService(new RailClient(new RailProperties(RailProperties.DEFAULT_BASE, KEY), config, upstream(status, "secret-upstream-body"), Duration.ofMillis(100)));
        assertThatThrownBy(() -> rail.board("SHF", 9, null, true)).isInstanceOfSatisfying(RailFailure.class,
            e -> { assertThat(e.getMessage()).doesNotContain("offline-departure-key", "secret-upstream-body");
                assertThat(e.reason).isEqualTo(status == 401 || status == 403 ? RailFailure.Reason.AUTHENTICATION : status == 429 ? RailFailure.Reason.RATE_LIMIT : RailFailure.Reason.UPSTREAM_ERROR); });
    }
    @Test void parsesCancelledCallingPointsIntoPartialDisruption() {
        String response = """
            {"crs":"SHF","locationName":"Sheffield","trainServices":[{"sta":"10:00","eta":"On time","isCancelled":false,
              "previousCallingPoints":[{"callingPoint":[{"locationName":"Derby","crs":"DBY","isCancelled":true}]}]}]}
            """;
        var rail = new RailService(client(upstream(200, response), KEY));
        var disruptions = RailDisruptions.from(rail.board("SHF", 9, null, false), "arrivals").disruptions();
        assertThat(disruptions).hasSize(1);
        assertThat(disruptions.getFirst().status()).isEqualTo("partially_cancelled");
        assertThat(disruptions.getFirst().affectedStops().getFirst().name()).isEqualTo("Derby");
    }
    @Test void disruptionBoardExplicitlyRequestsNineRows() {
        HttpClient http = upstream(200, BOARD);
        var controller = new RailController(new RailService(client(http, KEY)));
        assertThat(controller.disruptions("SHF", "arrivals").getStatusCode().value()).isEqualTo(200);
        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).sendAsync(captor.capture(), any());
        assertThat(captor.getValue().uri().getRawQuery()).isEqualTo("numRows=9");
    }
    @Test void malformedResponseIsRejected() {
        assertThatThrownBy(() -> new RailService(client(upstream(200,"<html>"),KEY)).arrivals("SHF",null,null)).isInstanceOfSatisfying(RailFailure.class,e -> assertThat(e.reason).isEqualTo(RailFailure.Reason.INVALID_RESPONSE));
    }
}
