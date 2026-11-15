package info.lifeinuk.backend.refresh;

import info.lifeinuk.backend.acquisition.NationalHighwaysRoadsLatestRefresh;
import info.lifeinuk.backend.acquisition.TflUndergroundLatestRefresh;
import info.lifeinuk.backend.roads.RoadsCurrentStateProjector;
import info.lifeinuk.backend.underground.UndergroundCurrentStateProjector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Both sources use a latest-only refresh: the provider fetch, the accepted parser, then the existing atomic
 * Current State replacement. No ingestion run or evidence artifact is written, so scheduled refreshes grow no
 * table. Qualification (and, for Roads, a usable key) is still checked on every run, so an un-qualified or
 * disabled endpoint stops refresh without a deploy. A failure never touches Current State.
 *
 * <p>Logs carry only the outcome and failure code; never keys, URLs or provider bodies.
 */
@Component
class TravelRefreshTasks {
    private static final Logger log = LoggerFactory.getLogger(TravelRefreshTasks.class);
    private final TflUndergroundLatestRefresh underground;
    private final NationalHighwaysRoadsLatestRefresh roads;

    TravelRefreshTasks(TflUndergroundLatestRefresh underground, NationalHighwaysRoadsLatestRefresh roads) {
        this.underground = underground;
        this.roads = roads;
    }

    RefreshPolicy.Outcome underground() {
        TflUndergroundLatestRefresh.Result result;
        try {
            result = underground.refresh();
        } catch (RuntimeException notStarted) {
            // e.g. endpoint no longer qualified/enabled: no request was made.
            log.warn("Underground refresh did not start: {}", notStarted.getClass().getSimpleName());
            return RefreshPolicy.Outcome.FAILED;
        }
        if (result.failureCode() != null) {
            return failed("Underground", result.failureCode(), result.rateLimited());
        }
        if (result.outcome() == UndergroundCurrentStateProjector.Outcome.CONFLICT) {
            log.warn("Underground refresh projection conflict");
            return RefreshPolicy.Outcome.FAILED;
        }
        log.info("Underground refresh succeeded: {}", result.outcome());
        return RefreshPolicy.Outcome.SUCCESS;
    }

    RefreshPolicy.Outcome roads() {
        NationalHighwaysRoadsLatestRefresh.Result result;
        try {
            result = roads.refresh();
        } catch (RuntimeException notStarted) {
            // e.g. no usable subscription key or endpoint no longer qualified: no request was made.
            log.warn("Roads refresh did not start: {}", notStarted.getClass().getSimpleName());
            return RefreshPolicy.Outcome.FAILED;
        }
        if (result.failureCode() != null) {
            return failed("Roads", result.failureCode(), result.rateLimited());
        }
        if (result.outcome() == RoadsCurrentStateProjector.Outcome.CONFLICT) {
            log.warn("Roads refresh projection conflict");
            return RefreshPolicy.Outcome.FAILED;
        }
        log.info("Roads refresh succeeded: {}", result.outcome());
        return RefreshPolicy.Outcome.SUCCESS;
    }

    private static RefreshPolicy.Outcome failed(String name, String code, boolean rateLimited) {
        log.warn("{} refresh failed: {}{}", name, code, rateLimited ? " (HTTP 429)" : "");
        return rateLimited ? RefreshPolicy.Outcome.RATE_LIMITED : RefreshPolicy.Outcome.FAILED;
    }
}
