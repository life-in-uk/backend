package info.lifeinuk.backend.acquisition;

import info.lifeinuk.backend.evidence.AcquisitionHistory;
import info.lifeinuk.backend.evidence.CapturedResponse;
import info.lifeinuk.backend.source.QualifiedSourceEndpoints;
import info.lifeinuk.backend.source.SourceEndpoint;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One explicit internal invocation = one logical run of current unplanned road closures, shared by all
 * users. Never triggered by a user request; no scheduling, retry or caller-supplied parameters.
 *
 * <p>Owner V1 decision (Issue #26): {@code closureType=unplanned}, {@code startDateTime = now - 6h},
 * {@code endDateTime = now}, both from one captured UTC instant in the contract's offset-free format, and
 * no {@code modifiedSinceDateTime}. Accepted limitation: if the provider filters by start time, a closure
 * that began more than six hours earlier but is still active may be omitted.
 */
@Service
public class NationalHighwaysRoadClosuresAcquisition {
    static final String CLOSURE_TYPE = "unplanned";
    static final Duration WINDOW = Duration.ofHours(6);
    /** Provider limit is 10 requests/minute per key; 7s spacing allows at most 9 in any rolling minute. */
    static final Duration MIN_REQUEST_SPACING = Duration.ofSeconds(7);
    /** Bounded run: a ninth continuation fails the run instead of extending it. */
    static final int MAX_PAGES = 8;
    static final DateTimeFormatter WINDOW_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final String HOST = URI.create(SourceEndpoint.NATIONAL_HIGHWAYS_ROAD_CLOSURES_URL).getHost();
    private static final String PATH = URI.create(SourceEndpoint.NATIONAL_HIGHWAYS_ROAD_CLOSURES_URL).getPath();
    private static final Pattern CURSOR = Pattern.compile("[A-Za-z0-9._~-]{1,200}");

    private final QualifiedSourceEndpoints endpoints;
    private final AcquisitionHistory history;
    private final NationalHighwaysHttp http;
    private final Clock clock;
    private final RequestPacer pacer;
    private final ReentrantLock sequential = new ReentrantLock();

    @Autowired
    NationalHighwaysRoadClosuresAcquisition(QualifiedSourceEndpoints endpoints, AcquisitionHistory history,
            NationalHighwaysHttp http) {
        this(endpoints, history, http, Clock.systemUTC(), RequestPacer.realTime(MIN_REQUEST_SPACING));
    }

    // Deterministic offline test seam: fixed acquisition instant and simulated pacing time.
    NationalHighwaysRoadClosuresAcquisition(QualifiedSourceEndpoints endpoints, AcquisitionHistory history,
            NationalHighwaysHttp http, Clock clock, RequestPacer pacer) {
        this.endpoints = endpoints;
        this.history = history;
        this.http = http;
        this.clock = clock;
        this.pacer = pacer;
    }

    /** Returns the run id. The run is SUCCESS only if every page was received and persisted together. */
    @Transactional(propagation = Propagation.NEVER)
    public UUID acquire() {
        sequential.lock();
        try {
            return acquireSequentially();
        } finally {
            sequential.unlock();
        }
    }

    private UUID acquireSequentially() {
        SourceEndpoint endpoint = endpoints.requireNationalHighwaysRoadClosures();
        http.requireCredential();
        Instant acquisitionNow = clock.instant();
        String windowQuery = "closureType=" + CLOSURE_TYPE
                + "&startDateTime=" + WINDOW_FORMAT.format(acquisitionNow.minus(WINDOW))
                + "&endDateTime=" + WINDOW_FORMAT.format(acquisitionNow);
        UUID runId = history.start(endpoint.getId());
        var captured = new ArrayList<CapturedResponse>();
        var usedCursors = new HashSet<String>();
        String cursor = null;
        int page = 0;
        try {
            while (true) {
                page++;
                if (page > MAX_PAGES) {
                    throw new AcquisitionFailure("PAGINATION_LIMIT",
                            "Provider continuation exceeded the " + MAX_PAGES + "-page acquisition bound");
                }
                String query = cursor == null ? windowQuery : windowQuery + "&pageCursor=" + cursor;
                pacer.awaitTurn();
                JsonEvidenceHttp.Response response = http.receive(endpoint, query);
                captured.add(new CapturedResponse(response.bytes(), response.mediaType(), response.requestedAt(),
                        response.observedAt(), 200, query, page));
                cursor = continuation(response.next());
                if (cursor == null) {
                    break;
                }
                if (!usedCursors.add(cursor)) {
                    throw new AcquisitionFailure("PAGINATION_LOOP", "Provider repeated a continuation cursor");
                }
            }
        } catch (AcquisitionFailure failure) {
            history.fail(runId, failure.code(), "Unplanned page " + page + ": " + failure.getMessage());
            return runId;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            history.fail(runId, "INTERRUPTED", "Unplanned page " + page + ": acquisition was interrupted");
            return runId;
        } catch (RuntimeException failure) {
            history.fail(runId, "TRANSPORT", "Unplanned page " + page + ": HTTP transport could not complete");
            return runId;
        }
        try {
            history.succeedWithResponses(runId, captured);
        } catch (RuntimeException persistenceFailure) {
            // The single success transaction rolled back: no artifact of a partial or failed run survives.
            history.fail(runId, "EVIDENCE_PERSISTENCE", "Evidence and successful completion could not be persisted");
        }
        return runId;
    }

    /** Provider-issued continuation only: the cursor is taken from x-next, never invented. Null ends paging. */
    static String continuation(List<String> next) {
        if (next.isEmpty() || (next.size() == 1 && next.getFirst().isBlank())) {
            return null;
        }
        if (next.size() != 1) {
            throw new AcquisitionFailure("PAGINATION_INVALID", "Expected at most one x-next continuation");
        }
        URI link;
        try {
            link = new URI(next.getFirst().strip());
        } catch (URISyntaxException invalid) {
            throw new AcquisitionFailure("PAGINATION_INVALID", "Continuation is not a valid URI");
        }
        if (!"https".equalsIgnoreCase(link.getScheme()) || link.getRawUserInfo() != null || link.getPort() != -1
                || !HOST.equalsIgnoreCase(link.getHost()) || !PATH.equals(link.getRawPath())
                || link.getRawFragment() != null || link.getRawQuery() == null) {
            throw new AcquisitionFailure("PAGINATION_INVALID", "Continuation must target the canonical road closures endpoint");
        }
        String cursor = null;
        for (String parameter : link.getRawQuery().split("&")) {
            int separator = parameter.indexOf('=');
            String name = separator < 0 ? parameter : parameter.substring(0, separator);
            if (name.equalsIgnoreCase("pageCursor")) {
                if (cursor != null || separator < 0) {
                    throw new AcquisitionFailure("PAGINATION_INVALID", "Continuation must carry exactly one cursor");
                }
                cursor = parameter.substring(separator + 1);
            }
        }
        if (cursor == null || !CURSOR.matcher(cursor).matches()) {
            throw new AcquisitionFailure("PAGINATION_INVALID", "Continuation must carry exactly one valid cursor");
        }
        return cursor;
    }
}
