package info.lifeinuk.backend.source;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SourceConfigurationTest {
    private SourceEndpoint endpoint() {
        return SourceEndpoint.bankHolidays(new Source(Source.BANK_HOLIDAYS_KEY, "GOV.UK Bank Holidays"), Instant.EPOCH);
    }

    @Test
    void configurationIsNotQualificationAndBothEnabledFlagsMatter() {
        SourceEndpoint endpoint = endpoint();
        assertThat(endpoint.isEnabled()).isTrue();
        assertThat(endpoint.isQualifiedAndEnabled()).isFalse();
        endpoint.qualify("Owner approval for the smoke test", "Attribution required; approved bounded retention");
        assertThat(endpoint.isQualifiedAndEnabled()).isTrue();
        endpoint.setEnabled(false);
        assertThat(endpoint.isQualifiedAndEnabled()).isFalse();
        endpoint.setEnabled(true);
        endpoint.getSource().setEnabled(false);
        assertThat(endpoint.isQualifiedAndEnabled()).isFalse();
    }

    @Test
    void invalidRequiredConfigurationIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Source("", "GOV.UK"));
        assertThatIllegalArgumentException().isThrownBy(() -> new Source("bad key", "GOV.UK"));
        assertThatIllegalArgumentException().isThrownBy(() -> new Source("valid-key", " "));
        assertThatIllegalArgumentException().isThrownBy(() -> endpoint().qualify(" ", "policy"));
        assertThatIllegalArgumentException().isThrownBy(() -> endpoint().qualify("approval", " "));
        assertThatNullPointerException().isThrownBy(() -> endpoint().setNextPollAt(null));
        assertThatIllegalArgumentException().isThrownBy(() -> SourceEndpoint.bankHolidays(
                new Source("other-source", "Other"), Instant.EPOCH));
        assertThatIllegalArgumentException().isThrownBy(() -> new BankHolidaysBootstrapProperties(true, "", "policy"));
        assertThatIllegalArgumentException().isThrownBy(() -> new BankHolidaysBootstrapProperties(true, "approval", ""));
    }

    @Test
    void endpointAndRollingCalendarPolicyAreExplicit() {
        SourceEndpoint endpoint = endpoint();
        assertThat(endpoint.getUrl()).isEqualTo("https://www.gov.uk/bank-holidays.json");
        assertThat(endpoint.getCalendarScope()).isEqualTo(CalendarScope.CURRENT_YEAR);
        assertThat(endpoint.getPollIntervalSeconds()).isEqualTo(86400);
        assertThat(endpoint.calendarYear(Clock.fixed(Instant.parse("2026-12-31T23:59:59Z"), ZoneOffset.UTC))).isEqualTo(2026);
        assertThat(endpoint.calendarYear(Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC))).isEqualTo(2027);
    }
}
