package info.lifeinuk.backend.refresh;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import info.lifeinuk.backend.acquisition.NationalHighwaysRoadsLatestRefresh;
import info.lifeinuk.backend.acquisition.TflUndergroundLatestRefresh;
import info.lifeinuk.backend.roads.RoadsCurrentStateProjector;
import info.lifeinuk.backend.underground.UndergroundCurrentStateProjector;
import org.junit.jupiter.api.Test;

class TravelRefreshTasksTest {
    private final TflUndergroundLatestRefresh underground = mock(TflUndergroundLatestRefresh.class);
    private final NationalHighwaysRoadsLatestRefresh roads = mock(NationalHighwaysRoadsLatestRefresh.class);
    private final TravelRefreshTasks tasks = new TravelRefreshTasks(underground, roads);

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
        verifyNoInteractions(roads);
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

    private static NationalHighwaysRoadsLatestRefresh.Result roadsProjected(RoadsCurrentStateProjector.Outcome outcome) {
        return new NationalHighwaysRoadsLatestRefresh.Result(outcome, null, null);
    }

    @Test
    void roadsSuccessIsTheLatestOnlyRefresh() {
        when(roads.refresh()).thenReturn(roadsProjected(RoadsCurrentStateProjector.Outcome.APPLIED));
        assertThat(tasks.roads()).isEqualTo(RefreshPolicy.Outcome.SUCCESS);
        verify(roads).refresh();
        verifyNoInteractions(underground);
    }

    @Test
    void roadsFailureOrConflictIsAFailureAnd429IsRateLimited() {
        when(roads.refresh()).thenReturn(new NationalHighwaysRoadsLatestRefresh.Result(null, "INVALID_RESPONSE", "x"));
        assertThat(tasks.roads()).isEqualTo(RefreshPolicy.Outcome.FAILED);
        when(roads.refresh()).thenReturn(roadsProjected(RoadsCurrentStateProjector.Outcome.CONFLICT));
        assertThat(tasks.roads()).isEqualTo(RefreshPolicy.Outcome.FAILED);
        when(roads.refresh()).thenReturn(new NationalHighwaysRoadsLatestRefresh.Result(null, "HTTP_STATUS",
                "Unplanned page 1: Expected HTTP 200; received 429"));
        assertThat(tasks.roads()).isEqualTo(RefreshPolicy.Outcome.RATE_LIMITED);
    }

    @Test
    void roadsMissingKeyFailsWithoutThrowing() {
        when(roads.refresh()).thenThrow(new IllegalStateException("A usable National Highways subscription key is not configured"));
        assertThat(tasks.roads()).isEqualTo(RefreshPolicy.Outcome.FAILED);
    }
}
