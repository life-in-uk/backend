package info.lifeinuk.backend.rail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RailConcurrencyTest {
    private static final byte[] BOARD = "{\"locationName\":\"Sheffield\",\"crs\":\"SHF\"}".getBytes(StandardCharsets.UTF_8);

    @Test void fourConcurrentRequestsAreAcceptedFifthIsRejectedAndPermitsAreReleased() throws Exception {
        RailClient client = mock(RailClient.class);
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        when(client.arrivals(anyString(), any(), any())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out");
            return BOARD;
        });
        var service = new RailService(client);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<java.util.concurrent.Future<RailResponse>>();
            try {
                for (int i = 0; i < 4; i++) tasks.add(pool.submit(() -> service.board("SHF", null, null, false)));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.board("SHF", null, null, true))
                        .isInstanceOfSatisfying(RailFailure.class, failure -> assertThat(failure.reason).isEqualTo(RailFailure.Reason.RATE_LIMIT));
                verify(client, never()).departures(anyString(), any(), any());
            } finally { release.countDown(); }
            for (var task : tasks) assertThat(task.get(5, TimeUnit.SECONDS).crs()).isEqualTo("SHF");
            assertThat(service.board("SHF", null, null, false).crs()).isEqualTo("SHF");
        }
    }

    @Test void upstreamAndParsingFailuresReleasePermits() {
        RailClient client = mock(RailClient.class);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(client.arrivals(anyString(), any(), any())).thenAnswer(invocation -> {
            int call = calls.getAndIncrement();
            if (call < 5) throw new RailFailure(RailFailure.Reason.NETWORK);
            if (call < 10) return "invalid".getBytes(StandardCharsets.UTF_8);
            return BOARD;
        });
        var service = new RailService(client);
        for (int i = 0; i < 5; i++) assertThatThrownBy(() -> service.arrivals("SHF", null, null))
                .isInstanceOfSatisfying(RailFailure.class, e -> assertThat(e.reason).isEqualTo(RailFailure.Reason.NETWORK));
        for (int i = 0; i < 5; i++) assertThatThrownBy(() -> service.arrivals("SHF", null, null))
                .isInstanceOfSatisfying(RailFailure.class, e -> assertThat(e.reason).isEqualTo(RailFailure.Reason.INVALID_RESPONSE));
        for (int i = 0; i < 5; i++) assertThat(service.arrivals("SHF", null, null).crs()).isEqualTo("SHF");
    }
}
