package info.lifeinuk.backend.evidence;

import info.lifeinuk.backend.source.Source;
import info.lifeinuk.backend.source.SourceEndpoint;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EvidenceModelTest {
    private SourceEndpoint endpoint() {
        SourceEndpoint endpoint = SourceEndpoint.bankHolidays(new Source(Source.BANK_HOLIDAYS_KEY, "GOV.UK"), Instant.EPOCH);
        endpoint.qualify("Owner decision", "Approved retention");
        return endpoint;
    }

    @Test
    void qualificationAndRequiredFieldsAreEnforced() {
        SourceEndpoint endpoint = endpoint();
        endpoint.setEnabled(false);
        assertThatIllegalStateException().isThrownBy(() -> IngestionRun.start(endpoint, Instant.EPOCH));
        endpoint.setEnabled(true);
        endpoint.getSource().setEnabled(false);
        assertThatIllegalStateException().isThrownBy(() -> IngestionRun.start(endpoint, Instant.EPOCH));
        assertThatNullPointerException().isThrownBy(() -> IngestionRun.start(null, Instant.EPOCH));
        assertThatNullPointerException().isThrownBy(() -> IngestionRun.start(endpoint(), null));
    }

    @Test
    void invalidCompletionAndFailureDoNotPartiallyChangeRun() {
        IngestionRun run = IngestionRun.start(endpoint(), Instant.EPOCH);
        assertThatIllegalArgumentException().isThrownBy(() -> run.succeed(null));
        assertThatIllegalArgumentException().isThrownBy(() -> run.succeed(Instant.EPOCH.minusSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> run.fail(Instant.EPOCH, " ", "Failure"));
        assertThatIllegalArgumentException().isThrownBy(() -> run.fail(Instant.EPOCH, "x".repeat(101), "Failure"));
        assertThatIllegalArgumentException().isThrownBy(() -> run.fail(Instant.EPOCH, "FAIL", "x".repeat(1001)));
        assertThat(run.getStatus()).isEqualTo(RunStatus.STARTED);
        assertThat(run.getCompletedAt()).isNull();
        run.succeed(Instant.EPOCH);
        assertThatIllegalStateException().isThrownBy(() -> run.fail(Instant.EPOCH, "FAIL", "Failure"));
    }

    @Test
    void rawEvidenceValidationAndCopiesAreExplicit() {
        IngestionRun run = IngestionRun.start(endpoint(), Instant.EPOCH);
        assertThatNullPointerException().isThrownBy(() -> new EvidenceArtifact(null, new byte[]{1}, "application/json", Instant.EPOCH));
        assertThatIllegalArgumentException().isThrownBy(() -> new EvidenceArtifact(run, null, "application/json", Instant.EPOCH));
        assertThatIllegalArgumentException().isThrownBy(() -> new EvidenceArtifact(run, new byte[0], "application/json", Instant.EPOCH));
        assertThatIllegalArgumentException().isThrownBy(() -> new EvidenceArtifact(run, new byte[]{1}, " ", Instant.EPOCH));
        assertThatIllegalArgumentException().isThrownBy(() -> new EvidenceArtifact(run, new byte[]{1}, "x".repeat(201), Instant.EPOCH));
        assertThatIllegalArgumentException().isThrownBy(() -> new EvidenceArtifact(run, new byte[]{1}, "application/json", null));
        assertThatIllegalArgumentException().isThrownBy(() -> new EvidenceArtifact(run, new byte[]{1}, "application/json", Instant.EPOCH.minusSeconds(1)));
        run.succeed(Instant.EPOCH);
        assertThatIllegalArgumentException().isThrownBy(() -> new EvidenceArtifact(run, new byte[]{1}, "application/json", Instant.EPOCH.plusSeconds(1)));
        byte[] input = {1};
        EvidenceArtifact artifact = new EvidenceArtifact(run, input, "application/json", Instant.EPOCH);
        input[0] = 2;
        artifact.getPayload()[0] = 3;
        assertThat(artifact.getPayload()).containsExactly((byte) 1);
    }
}
