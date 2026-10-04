package info.lifeinuk.backend.roads;

import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** One SELECT reads metadata and all facts atomically, including during replacement. */
@Component
public class RoadsCurrentStates {
    private final RoadsCurrentStateStore store;
    RoadsCurrentStates(RoadsCurrentStateStore store) { this.store = store; }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<RoadsCurrentState> current() { return store.read(false); }
}
