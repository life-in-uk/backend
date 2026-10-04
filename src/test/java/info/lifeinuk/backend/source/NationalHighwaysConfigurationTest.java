package info.lifeinuk.backend.source;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

class NationalHighwaysConfigurationTest {
    @Test
    void canonicalFactoryHasStableProviderEndpointAndScopeIdentity() {
        Source source = Source.nationalHighways();
        SourceEndpoint endpoint = SourceEndpoint.nationalHighwaysRoadClosures(source);
        assertThat(source.getKey()).isEqualTo("national-highways");
        assertThat(source.getDisplayName()).isEqualTo("National Highways");
        assertThat(source.getScope()).isEqualTo("NATIONAL_HIGHWAYS_ROAD_CLOSURES")
                .isNotIn(Source.BANK_HOLIDAYS_SCOPE, Source.TFL_UNDERGROUND_SCOPE);
        assertThat(endpoint.getSource()).isSameAs(source);
        assertThat(endpoint.getKey()).isEqualTo("national-highways-road-closures");
        assertThat(SourceEndpoint.NATIONAL_HIGHWAYS_ROADS_BASE_URL).isEqualTo("https://api.data.nationalhighways.co.uk/roads/v2.0");
        assertThat(endpoint.getUrl()).isEqualTo("https://api.data.nationalhighways.co.uk/roads/v2.0/closures")
                .doesNotContain("?", "closureType", "pageCursor", "subscription", "key");
        assertThat(endpoint.hasCanonicalNationalHighwaysIdentity()).isTrue();
        assertThat(endpoint.hasCanonicalTflIdentity()).isFalse();
        assertThat(endpoint.getCalendarScope()).isNull();
        assertThat(endpoint.getPollIntervalSeconds()).isNull();
        assertThat(endpoint.getNextPollAt()).isNull();
        assertThatIllegalStateException().isThrownBy(() -> endpoint.calendarYear(Clock.systemUTC()));
    }

    @Test
    void factoryRejectsAnyOtherSourceOrScope() {
        assertThatIllegalArgumentException().isThrownBy(() -> SourceEndpoint.nationalHighwaysRoadClosures(Source.tfl()));
        assertThatIllegalArgumentException().isThrownBy(() -> SourceEndpoint.nationalHighwaysRoadClosures(
                new Source(Source.BANK_HOLIDAYS_KEY, "GOV.UK Bank Holidays")));
        // Correct key with the wrong (Bank Holidays) scope is still not the canonical provider.
        assertThatIllegalArgumentException().isThrownBy(() -> SourceEndpoint.nationalHighwaysRoadClosures(
                new Source(Source.NATIONAL_HIGHWAYS_KEY, "National Highways")));
        assertThatIllegalArgumentException().isThrownBy(() -> SourceEndpoint.tflUnderground(Source.nationalHighways()));
        assertThatIllegalArgumentException().isThrownBy(() -> SourceEndpoint.bankHolidays(Source.nationalHighways(), java.time.Instant.EPOCH));
    }

    @Test
    void newConfigurationIsPendingWithAnUnapprovedDraftPolicyNotPermission() {
        SourceEndpoint endpoint = SourceEndpoint.nationalHighwaysRoadClosures(Source.nationalHighways());
        assertThat(endpoint.getQualificationStatus()).isEqualTo(QualificationStatus.PENDING);
        assertThat(endpoint.getQualificationRecord()).isNull();
        assertThat(endpoint.isQualifiedAndEnabled()).isFalse();
        assertThat(endpoint.getAttributionReference()).isEqualTo(SourceEndpoint.NATIONAL_HIGHWAYS_REFERENCE)
                .isEqualTo("https://developer.data.nationalhighways.co.uk/terms");
        assertThat(endpoint.getUseRetentionPolicy()).startsWith(SourceEndpoint.PENDING_POLICY)
                .contains("Road and Lane Closures Data Service (DATEX II) v2.0", "GET /closures",
                        "planned and", "unplanned closures are query modes of this one endpoint", "JSON DATEX II",
                        "never persisted, logged, returned or placed in a URL", "No raw-evidence retention duration",
                        "reviewed and recorded by the owner before qualification", "no acquisition");
    }

    @Test
    void explicitOwnerQualificationIsTheOnlyWayToBecomeEligibleAndBothEnabledFlagsMatter() {
        SourceEndpoint endpoint = SourceEndpoint.nationalHighwaysRoadClosures(Source.nationalHighways());
        endpoint.qualify("Owner reviewed National Highways road closures contract, terms and use",
                SourceEndpoint.NATIONAL_HIGHWAYS_USE_RETENTION_POLICY);
        assertThat(endpoint.isQualifiedAndEnabled()).isTrue();
        endpoint.setEnabled(false);
        assertThat(endpoint.isQualifiedAndEnabled()).isFalse();
        endpoint.setEnabled(true);
        endpoint.getSource().setEnabled(false);
        assertThat(endpoint.isQualifiedAndEnabled()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"qualificationRecord", "useRetentionPolicy"})
    void missingOwnerDecisionCannotMasqueradeAsPermission(String field) {
        SourceEndpoint endpoint = SourceEndpoint.nationalHighwaysRoadClosures(Source.nationalHighways());
        endpoint.qualify("Fixture owner approval", SourceEndpoint.NATIONAL_HIGHWAYS_USE_RETENTION_POLICY);
        for (String missing : new String[]{null, "", " ", " "}) {
            ReflectionTestUtils.setField(endpoint, field, missing);
            assertThat(endpoint.isQualifiedAndEnabled()).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "url=https://api.data.nationalhighways.co.uk/roads/v2.0/closures?closureType=planned",
        "url=https://api.data.nationalhighways.co.uk/roads/v1.0/closures",
        "url=http://api.data.nationalhighways.co.uk/roads/v2.0/closures",
        "url=https://example.invalid/roads/v2.0/closures",
        "key=national-highways-road-closures-planned"
    })
    void qualifiedButNoncanonicalIdentityIsNotEligible(String change) {
        SourceEndpoint endpoint = SourceEndpoint.nationalHighwaysRoadClosures(Source.nationalHighways());
        endpoint.qualify("Fixture owner approval", SourceEndpoint.NATIONAL_HIGHWAYS_USE_RETENTION_POLICY);
        String[] assignment = change.split("=", 2);
        ReflectionTestUtils.setField(endpoint, assignment[0], assignment[1]);
        assertThat(endpoint.isQualifiedAndEnabled()).isFalse();
    }

    @Test
    void explicitBootstrapApprovalRequiresBothOwnerRecordAndPolicy() {
        assertThatIllegalArgumentException().isThrownBy(() -> new NationalHighwaysBootstrapProperties(true, "", "policy"));
        assertThatIllegalArgumentException().isThrownBy(() -> new NationalHighwaysBootstrapProperties(true, "owner", " "));
        assertThatIllegalArgumentException().isThrownBy(() -> new NationalHighwaysBootstrapProperties(true, null, "policy"));
        assertThat(new NationalHighwaysBootstrapProperties(false, "", "").qualified()).isFalse();
    }
}
