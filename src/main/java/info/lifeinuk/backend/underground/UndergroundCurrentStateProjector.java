package info.lifeinuk.backend.underground;

import java.util.HashSet;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Supplied normalized facts only; one locked, atomic latest-known snapshot replacement. */
@Service
public class UndergroundCurrentStateProjector {
    public enum Outcome { APPLIED, IGNORED_OLDER, REPLAYED, CONFLICT }
    private final UndergroundCurrentStateStore store;

    UndergroundCurrentStateProjector(UndergroundCurrentStateStore store) { this.store = store; }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Outcome project(UndergroundStatusInterpretation input) {
        Objects.requireNonNull(input, "Interpretation is required");
        var identities = new HashSet<String>();
        for (var line : input.lines()) {
            if (!identities.add(line.lineId())) {
                throw new IllegalArgumentException("Duplicate line identity");
            }
        }
        boolean first = store.insertFirst(input);
        var current = store.lock();
        if (!first) {
            int order = input.observedAt().compareTo(current.observedAt());
            if (order < 0) { return Outcome.IGNORED_OLDER; }
            if (order == 0) {
                if (!input.evidenceArtifactId().equals(current.evidenceArtifactId())) { return Outcome.CONFLICT; }
                return input.lines().equals(store.withLines(current).lines()) ? Outcome.REPLAYED : Outcome.CONFLICT;
            }
            if (input.evidenceArtifactId().equals(current.evidenceArtifactId())) { return Outcome.CONFLICT; }
        }
        store.replace(input);
        return Outcome.APPLIED;
    }
}
