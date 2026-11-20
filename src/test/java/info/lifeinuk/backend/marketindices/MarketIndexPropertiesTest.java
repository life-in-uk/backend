package info.lifeinuk.backend.marketindices;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MarketIndexPropertiesTest {
    static MarketIndexProperties defaults() {
        return with("", List.of(), false, List.of(), Duration.ofDays(30), Duration.ofDays(730));
    }

    static MarketIndexProperties with(String keys, List<String> accepted, boolean publicEnabled, List<String> publicSources,
            Duration backfill, Duration retention) {
        return new MarketIndexProperties(keys, accepted, publicEnabled, publicSources, "", backfill, retention,
                Duration.ofMinutes(20), Duration.ofMinutes(60), Duration.ofMinutes(30), Duration.ofMinutes(6),
                Duration.ofMinutes(2), Duration.ofMinutes(5));
    }

    @Test
    void defaultsAreOff() {
        var p = defaults();
        assertThat(p.collectorKeys()).isEmpty();
        assertThat(p.acceptedSources()).isEmpty();
        assertThat(p.publicEnabled()).isFalse();
        assertThat(p.publicSources()).isEmpty();
        assertThat(p.toString()).doesNotContain("collectorKeys=k");
    }

    @Test
    void mockCanNeverBePublic() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                with("", List.of("mock"), false, List.of("mock"), Duration.ofDays(30), Duration.ofDays(730)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                with("", List.of("mock"), true, List.of("provider-x", "mock"), Duration.ofDays(30), Duration.ofDays(730)));
    }

    @Test
    void publicReadNeedsASource() {
        assertThatIllegalArgumentException().isThrownBy(() -> with("", List.of(), true, List.of(), Duration.ofDays(30), Duration.ofDays(730)));
        assertThat(with("", List.of(), true, List.of("provider-x"), Duration.ofDays(30), Duration.ofDays(730)).publicEnabled()).isTrue();
    }

    @Test
    void sourcesMustBeIdentifiers() {
        assertThatIllegalArgumentException().isThrownBy(() -> with("", List.of("Provider X"), false, List.of(), Duration.ofDays(30), Duration.ofDays(730)));
        assertThat(with("", List.of(" mock ", "", "mock"), false, List.of(), Duration.ofDays(30), Duration.ofDays(730)).acceptedSources())
                .containsExactly("mock");
    }

    @Test
    void backfillAndRetentionAreBoundedAndOrdered() {
        assertThatIllegalArgumentException().isThrownBy(() -> with("", List.of(), false, List.of(), Duration.ofMinutes(59), Duration.ofDays(730)));
        assertThatIllegalArgumentException().isThrownBy(() -> with("", List.of(), false, List.of(), Duration.ofDays(366), Duration.ofDays(730)));
        assertThatIllegalArgumentException().isThrownBy(() -> with("", List.of(), false, List.of(), Duration.ofDays(30), Duration.ofDays(29)));
        assertThatIllegalArgumentException().isThrownBy(() -> with("", List.of(), false, List.of(), Duration.ofDays(30), Duration.ofDays(3651)));
        assertThatIllegalArgumentException().isThrownBy(() -> with("", List.of(), false, List.of(), Duration.ofDays(60), Duration.ofDays(60)));
        assertThat(with("", List.of(), false, List.of(), Duration.ofDays(365), Duration.ofDays(366)).retention()).isEqualTo(Duration.ofDays(366));
        assertThat(with("", List.of(), false, List.of(), Duration.ofHours(1), Duration.ofDays(30)).maxBackfill()).isEqualTo(Duration.ofHours(1));
    }
}
