package info.lifeinuk.backend.source;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

class TflUndergroundConfigurationTest {
    @Test
    void canonicalFactoryHasControlledIdentityAndNoCalendarOrPollingPolicy() {
        Source source = Source.tfl();
        SourceEndpoint endpoint = SourceEndpoint.tflUnderground(source);
        assertThat(source.getKey()).isEqualTo("transport-for-london");
        assertThat(source.getDisplayName()).isEqualTo("Transport for London");
        assertThat(source.getScope()).isEqualTo("TFL_UNDERGROUND_STATUS").isNotEqualTo(Source.BANK_HOLIDAYS_SCOPE);
        assertThat(endpoint.getSource()).isSameAs(source);
        assertThat(endpoint.getKey()).isEqualTo("tfl-underground-status");
        assertThat(endpoint.getUrl()).isEqualTo("https://api.tfl.gov.uk/Line/Mode/tube/Status").doesNotContain("?", "app_key", "app_id");
        assertThat(endpoint.getCalendarScope()).isNull();
        assertThat(endpoint.getPollIntervalSeconds()).isNull();
        assertThat(endpoint.getNextPollAt()).isNull();
        assertThat(endpoint.getQualificationStatus()).isEqualTo(QualificationStatus.PENDING);
        assertThat(endpoint.isQualifiedAndEnabled()).isFalse();
        assertThatIllegalStateException().isThrownBy(() -> endpoint.calendarYear(Clock.systemUTC()));
        assertThatIllegalArgumentException().isThrownBy(() -> SourceEndpoint.tflUnderground(new Source("other", "Other")));
        assertThatIllegalArgumentException().isThrownBy(() -> SourceEndpoint.tflUnderground(new Source(Source.TFL_KEY, "Wrong scope")));
    }

    @Test
    void pendingDraftPolicyExpressesLifecycleWithoutInventingOwnerApproval() {
        SourceEndpoint endpoint = SourceEndpoint.tflUnderground(Source.tfl());
        assertThat(endpoint.getQualificationRecord()).isNull();
        assertThat(endpoint.getUseRetentionPolicy()).startsWith(SourceEndpoint.PENDING_POLICY)
                .contains("high-frequency", "immutable while retained", "72 hours", "EvidenceArtifact observation time",
                        "not filesystem", "Current State is independent", "meaningful normalized Change History",
                        "unchanged polling observations must not become permanent", "TfL licence/terms override");
        assertThat(endpoint.getAttributionReference()).isEqualTo(SourceEndpoint.TFL_TERMS_REFERENCE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"qualificationRecord", "useRetentionPolicy"})
    void missingRequiredOwnerDecisionCannotMasqueradeAsPermission(String field) {
        SourceEndpoint endpoint = SourceEndpoint.tflUnderground(Source.tfl());
        endpoint.qualify("Fixture owner approval", SourceEndpoint.TFL_USE_RETENTION_POLICY);
        assertThat(endpoint.isQualifiedAndEnabled()).isTrue();
        for (String missing : new String[]{null, "", " ", "\u2003"}) {
            ReflectionTestUtils.setField(endpoint, field, missing);
            assertThat(endpoint.isQualifiedAndEnabled()).isFalse();
        }
    }

    @Test
    void explicitBootstrapApprovalRequiresBothOwnerRecordAndPolicy() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TflUndergroundBootstrapProperties(true, "", "policy"));
        assertThatIllegalArgumentException().isThrownBy(() -> new TflUndergroundBootstrapProperties(true, "owner", " "));
        assertThatIllegalArgumentException().isThrownBy(() -> SourceEndpoint.tflUnderground(Source.tfl()).qualify("owner", ""));
        assertThat(new TflUndergroundBootstrapProperties(false, "", "").qualified()).isFalse();
    }
}
