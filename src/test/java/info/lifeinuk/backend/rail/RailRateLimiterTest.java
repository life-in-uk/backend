package info.lifeinuk.backend.rail;

import static info.lifeinuk.backend.rail.RailRateLimiter.Decision.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RailRateLimiterTest {
    private static final byte[] BOARD = "{\"locationName\":\"Sheffield\",\"crs\":\"SHF\",\"generatedAt\":\"2026-11-11T10:00:00Z\"}"
            .getBytes(StandardCharsets.UTF_8);
    private final AtomicLong millis = new AtomicLong(Instant.parse("2026-11-11T10:00:05Z").toEpochMilli());
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
    };

    @Test
    void perClientBudgetIsEnforcedPerMinuteWindow() {
        var limiter = new RailRateLimiter(new RailRateLimiter.Limits(3, 10), clock);
        for (int i = 0; i < 3; i++) assertThat(limiter.tryAcquire("203.0.113.1")).isEqualTo(ALLOWED);
        assertThat(limiter.tryAcquire("203.0.113.1")).isEqualTo(CLIENT_LIMITED);
        assertThat(limiter.tryAcquire("203.0.113.2")).isEqualTo(ALLOWED);
        assertThat(limiter.retryAfterSeconds()).isEqualTo(55);
        millis.addAndGet(55_000);
        assertThat(limiter.tryAcquire("203.0.113.1")).isEqualTo(ALLOWED);
    }

    @Test
    void aggregateBudgetCapsAllClients() {
        var limiter = new RailRateLimiter(new RailRateLimiter.Limits(2, 4), clock);
        assertThat(limiter.tryAcquire("a")).isEqualTo(ALLOWED);
        assertThat(limiter.tryAcquire("b")).isEqualTo(ALLOWED);
        assertThat(limiter.tryAcquire("c")).isEqualTo(ALLOWED);
        assertThat(limiter.tryAcquire("d")).isEqualTo(ALLOWED);
        assertThat(limiter.tryAcquire("e")).isEqualTo(AGGREGATE_LIMITED);
        assertThat(limiter.tryAcquire("a")).isEqualTo(AGGREGATE_LIMITED);
    }

    @Test
    void limitsAreValidated() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RailRateLimiter.Limits(0, 10));
        assertThatIllegalArgumentException().isThrownBy(() -> new RailRateLimiter.Limits(10, 5));
        assertThatIllegalArgumentException().isThrownBy(() -> new RailRateLimiter.Limits(6, 601));
        assertThat(RailRateLimiter.Limits.defaults()).isEqualTo(new RailRateLimiter.Limits(6, 60));
    }

    @Test
    void controllerRejectsOverBudgetClientsBeforeContactingTheProvider() throws Exception {
        RailClient client = mock(RailClient.class);
        when(client.arrivals(anyString(), any(), any())).thenReturn(BOARD);
        var controller = new RailController(new RailService(client), new RailRateLimiter(new RailRateLimiter.Limits(2, 10), clock));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/api/rail/stations/SHF/disruptions").with(r -> { r.setRemoteAddr("198.51.100.7"); return r; }))
                    .andExpect(status().isOk());
        }
        // Invalid requests are rejected by validation and do not consume budget.
        mvc.perform(get("/api/rail/stations/S1/disruptions").with(r -> { r.setRemoteAddr("198.51.100.7"); return r; }))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/rail/stations/SHF/disruptions").with(r -> { r.setRemoteAddr("198.51.100.7"); return r; }))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Retry-After", "55"))
                .andExpect(jsonPath("$.code").value("RAIL_CLIENT_RATE_LIMIT"));
        verify(client, times(2)).arrivals(anyString(), any(), any());
        mvc.perform(get("/api/rail/stations/SHF/arrivals").with(r -> { r.setRemoteAddr("198.51.100.8"); return r; }))
                .andExpect(status().isOk());
    }

    @Test
    void controllerReturns503WhenTheInstanceBudgetIsSpent() {
        RailClient client = mock(RailClient.class);
        when(client.arrivals(anyString(), any(), any())).thenReturn(BOARD);
        var controller = new RailController(new RailService(client), new RailRateLimiter(new RailRateLimiter.Limits(1, 1), clock));
        var first = new MockHttpServletRequest();
        first.setRemoteAddr("192.0.2.1");
        var second = new MockHttpServletRequest();
        second.setRemoteAddr("192.0.2.2");
        assertThat(controller.disruptions("SHF", "arrivals", first).getStatusCode().value()).isEqualTo(200);
        var limited = controller.disruptions("SHF", "arrivals", second);
        assertThat(limited.getStatusCode().value()).isEqualTo(503);
        assertThat(limited.getBody()).isEqualTo(new RailController.Error("RAIL_RATE_LIMIT", "Rail information is temporarily unavailable."));
        verify(client, times(1)).arrivals(anyString(), any(), any());
    }
}
