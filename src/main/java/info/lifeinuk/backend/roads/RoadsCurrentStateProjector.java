package info.lifeinuk.backend.roads;

import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Complete normalized logical snapshot only; comparison and replacement occur under one row lock. */
@Service
public class RoadsCurrentStateProjector {
    public enum Outcome { APPLIED, IGNORED_OLDER, REPLAYED, CONFLICT }
    private final RoadsCurrentStateStore store;

    RoadsCurrentStateProjector(RoadsCurrentStateStore store) { this.store = store; }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Outcome project(RoadsCurrentState input) {
        Objects.requireNonNull(input);
        if (input.ingestionRunId() == null || input.sourceEndpointId() == null || input.snapshotAt() == null
                || input.projectedAt() == null || input.pageCount() < 1 || input.pageCount() > 8) {
            throw new IllegalArgumentException("Complete snapshot provenance is required");
        }
        boolean first = store.insertFirst(input);
        var current = store.read(true).orElseThrow();
        if (!first) {
            int order = input.snapshotAt().compareTo(current.snapshotAt());
            if (order < 0) { return Outcome.IGNORED_OLDER; }
            if (order == 0) {
                return input.ingestionRunId().equals(current.ingestionRunId())
                        && input.sourceEndpointId().equals(current.sourceEndpointId())
                        && input.pageCount() == current.pageCount() && input.closures().equals(current.closures())
                        ? Outcome.REPLAYED : Outcome.CONFLICT;
            }
            if (input.ingestionRunId().equals(current.ingestionRunId())) { return Outcome.CONFLICT; }
            store.replace(input);
        }
        return Outcome.APPLIED;
    }
}
