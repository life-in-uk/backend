package info.lifeinuk.backend.underground;

import java.util.Optional;
import org.springframework.stereotype.Service;

/** Maps one coherent, committed Current State snapshot; no upstream dependencies. */
@Service
public class UndergroundQuery {
    private final UndergroundCurrentStates states;

    UndergroundQuery(UndergroundCurrentStates states) { this.states = states; }

    public Optional<UndergroundResponse> read() {
        return states.current().map(UndergroundResponse::from);
    }
}
