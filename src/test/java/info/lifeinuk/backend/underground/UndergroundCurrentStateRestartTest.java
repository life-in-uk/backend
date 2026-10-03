package info.lifeinuk.backend.underground;

import info.lifeinuk.backend.LifeInUkBackendApplication;
import info.lifeinuk.backend.support.IsolatedPostgres;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ConfigurableApplicationContext;
import static org.assertj.core.api.Assertions.*;

class UndergroundCurrentStateRestartTest {
    @Test
    void realApplicationRestartRetainsFullSnapshotAndOrdering() {
        String schema = "underground_restart_" + UUID.randomUUID().toString().replace("-", "");
        var input = new UndergroundStatusInterpretation(UUID.randomUUID(), Instant.parse("2026-01-01T00:00:00.123456789Z"),
                List.of(new UndergroundLineStatus("central", "Central", List.of(
                        new UndergroundOperationalStatus(9, " Delays — Café ", Optional.of(" Source reason ")),
                        new UndergroundOperationalStatus(10, "Next", Optional.empty()),
                        new UndergroundOperationalStatus(9, " Delays — Café ", Optional.of(" Source reason "))))));
        try (var first = start(schema)) {
            assertThat(first.getBean(UndergroundCurrentStates.class).current()).isEmpty();
            assertThat(first.getBean(UndergroundCurrentStateProjector.class).project(input))
                    .isEqualTo(UndergroundCurrentStateProjector.Outcome.APPLIED);
        }
        try (var second = start(schema)) {
            assertThat(second.getBean(UndergroundCurrentStates.class).current())
                    .contains(new UndergroundCurrentState(input.evidenceArtifactId(), input.observedAt(), input.lines()));
            assertThat(second.getBean(UndergroundCurrentStateProjector.class).project(input))
                    .isEqualTo(UndergroundCurrentStateProjector.Outcome.REPLAYED);
        }
    }

    private ConfigurableApplicationContext start(String schema) {
        return new SpringApplicationBuilder(LifeInUkBackendApplication.class).web(WebApplicationType.NONE)
                .initializers(new IsolatedPostgres.Initializer(), context -> TestPropertyValues.of(
                        "spring.flyway.schemas=" + schema, "spring.flyway.default-schema=" + schema,
                        "spring.jpa.properties.hibernate.default_schema=" + schema,
                        "spring.datasource.hikari.schema=" + schema).applyTo(context.getEnvironment())).run();
    }
}
