package info.lifeinuk.backend.refresh;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class TravelRefreshPropertiesTest {
    private static TravelRefreshProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("life-in-uk.travel-refresh", TravelRefreshProperties.class);
    }

    @Test
    void bothSchedulesAreOffByDefaultWithReviewedIntervals() {
        var properties = bind(Map.of());
        assertThat(properties.underground().enabled()).isFalse();
        assertThat(properties.roads().enabled()).isFalse();
        assertThat(properties.underground().interval()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.underground().maxBackoff()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.roads().interval()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.roads().maxBackoff()).isEqualTo(Duration.ofMinutes(60));
        assertThat(properties.underground().maxRuns()).isZero();
        assertThat(properties.underground().initialDelay()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void explicitValuesBind() {
        var properties = bind(Map.of(
                "life-in-uk.travel-refresh.underground.enabled", "true",
                "life-in-uk.travel-refresh.underground.interval", "PT3M",
                "life-in-uk.travel-refresh.underground.max-runs", "5",
                "life-in-uk.travel-refresh.roads.enabled", "true",
                "life-in-uk.travel-refresh.roads.max-backoff", "PT2H"));
        assertThat(properties.underground().enabled()).isTrue();
        assertThat(properties.underground().interval()).isEqualTo(Duration.ofMinutes(3));
        assertThat(properties.underground().maxRuns()).isEqualTo(5);
        assertThat(properties.roads().enabled()).isTrue();
        assertThat(properties.roads().interval()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.roads().maxBackoff()).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void intervalsBelowTheProviderFloorsFailStartup() {
        assertThatThrownBy(() -> bind(Map.of("life-in-uk.travel-refresh.underground.interval", "PT59S")))
                .isInstanceOf(BindException.class).rootCause().hasMessageContaining("at least PT1M");
        assertThatThrownBy(() -> bind(Map.of("life-in-uk.travel-refresh.roads.interval", "PT4M")))
                .isInstanceOf(BindException.class).rootCause().hasMessageContaining("at least PT5M");
    }

    @Test
    void otherOutOfRangeValuesFailStartup() {
        assertThatThrownBy(() -> bind(Map.of("life-in-uk.travel-refresh.underground.max-backoff", "PT1M")))
                .isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("life-in-uk.travel-refresh.roads.max-backoff", "PT7H")))
                .isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("life-in-uk.travel-refresh.roads.max-runs", "-1")))
                .isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("life-in-uk.travel-refresh.roads.initial-delay", "PT11M")))
                .isInstanceOf(BindException.class);
    }
}
