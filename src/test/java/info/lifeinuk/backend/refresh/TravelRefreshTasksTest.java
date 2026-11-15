package info.lifeinuk.backend.refresh;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import info.lifeinuk.backend.acquisition.NationalHighwaysRoadClosuresAcquisition;
import info.lifeinuk.backend.acquisition.TflUndergroundLatestRefresh;
import info.lifeinuk.backend.evidence.AcquisitionHistory;
import info.lifeinuk.backend.evidence.RunStatus;
import info.lifeinuk.backend.roads.RoadsCurrentStateProjector;
import info.lifeinuk.backend.roads.RoadsEvidenceProjection;
import info.lifeinuk.backend.underground.UndergroundCurrentStateProjector;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TravelRefreshTasksTest {
    private final TflUndergroundLatestRefresh underground = mock(TflUndergroundLatestRefresh.class);
    private final NationalHighwaysRoadClosuresAcquisition roads = mock(NationalHighwaysRoadClosuresAcquisition.class);
    private final RoadsEvidenceProjection roadsProjection = mock(RoadsEvidenceProjection.class);
    private final AcquisitionHistory history = mock(AcquisitionHistory.class);
    private final TravelRefreshTasks tasks = new TravelRefreshTasks(underground, roads, roadsProjection, history);
    private final UUID run = UUID.randomUUID();

    private static AcquisitionHistory.RunOutcome failed(String code, String message) {
        return new AcquisitionHistory.RunOutcome(RunStatus.FAILED, code, message);
    }

    private static TflUndergroundLatestRefresh.Result projected(UndergroundCurrentStateProjector.Outcome outcome) {
        return new TflUndergroundLatestRefresh.Result(outcome, null, null);
    }

    private static TflUndergroundLatestRefresh.Result failure(String code, String message) {
        return new TflUndergroundLatestRefresh.Result(null, code, message);
    }

    @Test
    void undergroundSuccessIsTheLatestOnlyRefresh() {
        when(underground.refresh()).thenReturn(projected(UndergroundCurrentStateProjector.Outcome.APPLIED));
        assertThat(tasks.underground()).isEqualTo(RefreshPolicy.Outcome.SUCCESS);
        verify(underground).refresh();
        verifyNoInteractions(roads, roadsProjection, history);
    }

    @Test
    void undergroundOlderOrReplayedSnapshotsAreNotFailures() {
        when(underground.refresh()).thenReturn(projected(UndergroundCurrentStateProjector.Outcome.IGNORED_OLDER));
        assertThat(tasks.underground()).isEqualTo(RefreshPolicy.Outcome.SUCCESS);
        when(underground.refresh()).thenReturn(projected(UndergroundCurrentStateProjector.Outcome.REPLAYED));
        assertThat(tasks.underground()).isEqualTo(RefreshPolicy.Outcome.SUCCESS);
    }

    @Test
    void undergroundInvalidResponseOrConflictIsAFailure() {
        when(underground.refresh()).thenReturn(failure("INVALID_RESPONSE", "TfL response did not match"));
        assertThat(tasks.underground()).isEqualTo(RefreshPolicy.Outcome.FAILED);
        when(underground.refresh()).thenReturn(projected(UndergroundCurrentStateProjector.Outcome.CONFLICT));
        assertThat(tasks.underground()).isEqualTo(RefreshPolicy.Outcome.FAILED);
    }

    @Test
    void undergroundHttp429IsRateLimitedOtherFailuresAreFailed() {
        when(underground.refresh()).thenReturn(failure("HTTP_STATUS", "Expected HTTP 200; received 429"));
        assertThat(tasks.underground()).isEqualTo(RefreshPolicy.Outcome.RATE_LIMITED);
        when(underground.refresh()).thenReturn(failure("HTTP_STATUS", "Expected HTTP 200; received 503"));
        assertThat(tasks.underground()).isEqualTo(RefreshPolicy.Outcome.FAILED);
        when(underground.refresh()).thenReturn(failure("TIMEOUT", "HTTP request timed out"));
        assertThat(tasks.underground()).isEqualTo(RefreshPolicy.Outcome.FAILED);
    }

    @Test
    void undergroundIneligibleEndpointFailsWithoutThrowing() {
        when(underground.refresh()).thenThrow(new IllegalStateException("not qualified"));
        assertThat(tasks.underground()).isEqualTo(RefreshPolicy.Outcome.FAILED);
    }

    @Test
    void roadsProjectsOnlyASuccessfulRun() {
        when(roads.acquire()).thenReturn(run);
        when(history.outcome(run)).thenReturn(new AcquisitionHistory.RunOutcome(RunStatus.SUCCESS, null, null));
        when(roadsProjection.projectRun(run)).thenReturn(RoadsCurrentStateProjector.Outcome.APPLIED);
        assertThat(tasks.roads()).isEqualTo(RefreshPolicy.Outcome.SUCCESS);
        verify(roadsProjection).projectRun(run);
        verifyNoInteractions(underground);
    }

    @Test
    void roadsFailedRunIsNeverProjected() {
        when(roads.acquire()).thenReturn(run);
        when(history.outcome(run)).thenReturn(failed("HTTP_STATUS", "Unplanned page 2: Expected HTTP 200; received 429"));
        assertThat(tasks.roads()).isEqualTo(RefreshPolicy.Outcome.RATE_LIMITED);
        when(history.outcome(run)).thenReturn(failed("PAGINATION_LIMIT", "Unplanned page 9: bound"));
        assertThat(tasks.roads()).isEqualTo(RefreshPolicy.Outcome.FAILED);
        verifyNoInteractions(roadsProjection);
    }

    @Test
    void roadsProjectionFailureOrConflictIsAFailure() {
        when(roads.acquire()).thenReturn(run);
        when(history.outcome(run)).thenReturn(new AcquisitionHistory.RunOutcome(RunStatus.SUCCESS, null, null));
        when(roadsProjection.projectRun(run)).thenThrow(new IllegalArgumentException("invalid"));
        assertThat(tasks.roads()).isEqualTo(RefreshPolicy.Outcome.FAILED);
        reset(roadsProjection);
        when(roadsProjection.projectRun(run)).thenReturn(RoadsCurrentStateProjector.Outcome.CONFLICT);
        assertThat(tasks.roads()).isEqualTo(RefreshPolicy.Outcome.FAILED);
    }

    @Test
    void roadsMissingKeyFailsWithoutARunOrThrowing() {
        when(roads.acquire()).thenThrow(new IllegalStateException("A usable National Highways subscription key is not configured"));
        assertThat(tasks.roads()).isEqualTo(RefreshPolicy.Outcome.FAILED);
        verifyNoInteractions(history, roadsProjection);
    }
}
