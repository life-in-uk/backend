package info.lifeinuk.backend.underground;

import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Internal read boundary: all relational rows come from one consistent database snapshot. */
@Component
public class UndergroundCurrentStates {
    private final UndergroundCurrentStateStore store;

    UndergroundCurrentStates(UndergroundCurrentStateStore store) { this.store = store; }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public Optional<UndergroundCurrentState> current() { return store.read(); }
}
