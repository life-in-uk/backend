package info.lifeinuk.backend.evidence;

import info.lifeinuk.backend.source.Source;
import info.lifeinuk.backend.source.SourceEndpoint;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Exact run identity only. Accepted #26 SUCCESS means all pages committed together. */
@Component
public class NationalHighwaysRunEvidence {
    private static final Pattern QUERY = Pattern.compile("closureType=unplanned&startDateTime=(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})&endDateTime=(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:&pageCursor=[A-Za-z0-9._~-]{1,200})?");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);
    private final EntityManager em;
    NationalHighwaysRunEvidence(EntityManager em) { this.em = em; }

    public record Run(UUID id, UUID endpointId, Instant startedAt, List<EvidenceArtifact> pages) {
        public Run { pages = List.copyOf(pages); }
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Run requireSuccessful(UUID id) {
        var run = em.createQuery("SELECT r FROM IngestionRun r JOIN FETCH r.sourceEndpoint e JOIN FETCH e.source WHERE r.id=:id", IngestionRun.class)
                .setParameter("id", id).getResultStream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Logical run does not exist"));
        var endpoint = run.getSourceEndpoint();
        var source = endpoint.getSource();
        if (run.getStatus() != RunStatus.SUCCESS || !endpoint.isQualifiedAndEnabled()
                || !Source.NATIONAL_HIGHWAYS_KEY.equals(source.getKey())
                || !Source.NATIONAL_HIGHWAYS_ROAD_CLOSURES_SCOPE.equals(source.getScope())
                || !SourceEndpoint.NATIONAL_HIGHWAYS_ROAD_CLOSURES_KEY.equals(endpoint.getKey())
                || !SourceEndpoint.NATIONAL_HIGHWAYS_ROAD_CLOSURES_URL.equals(endpoint.getUrl())) {
            throw new IllegalArgumentException("Expected successful qualified canonical National Highways run");
        }
        var pages = em.createQuery("""
                SELECT a FROM EvidenceArtifact a JOIN FETCH a.ingestionRun r
                JOIN FETCH r.sourceEndpoint e JOIN FETCH e.source WHERE r.id=:id ORDER BY a.pageNumber
                """, EvidenceArtifact.class).setParameter("id", id).getResultList();
        if (pages.isEmpty() || pages.size() > 8) { throw new IllegalArgumentException("Complete bounded pages are required"); }
        String window = null;
        for (int index = 0; index < pages.size(); index++) {
            var page = pages.get(index);
            if (!Integer.valueOf(index + 1).equals(page.getPageNumber()) || page.getRequestQuery() == null) {
                throw new IllegalArgumentException("Pages must be contiguous from one");
            }
            var matcher = QUERY.matcher(page.getRequestQuery());
            if (!matcher.matches() || (index == 0) == page.getRequestQuery().contains("&pageCursor=")) {
                throw new IllegalArgumentException("Expected captured unplanned acquisition window and continuation");
            }
            Instant start = LocalDateTime.parse(matcher.group(1), TIME).toInstant(ZoneOffset.UTC);
            Instant end = LocalDateTime.parse(matcher.group(2), TIME).toInstant(ZoneOffset.UTC);
            if (!Duration.between(start, end).equals(Duration.ofHours(6))) {
                throw new IllegalArgumentException("Expected six-hour unplanned window");
            }
            String thisWindow = matcher.group(1) + "/" + matcher.group(2);
            if (window != null && !window.equals(thisWindow)) { throw new IllegalArgumentException("Pages must share one window"); }
            window = thisWindow;
        }
        // Fully initialized provenance remains available after this read transaction closes.
        return new Run(run.getId(), endpoint.getId(), run.getStartedAt(), pages);
    }
}
