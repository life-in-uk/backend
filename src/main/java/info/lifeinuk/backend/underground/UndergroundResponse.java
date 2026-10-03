package info.lifeinuk.backend.underground;

import java.time.Instant;
import java.util.List;

/** Public source facts only; internal evidence identity stays in Current State. */
public record UndergroundResponse(Instant observedAt, List<Line> lines) {
    public UndergroundResponse { lines = List.copyOf(lines); }

    static UndergroundResponse from(UndergroundCurrentState state) {
        return new UndergroundResponse(state.observedAt(), state.lines().stream().map(line ->
                new Line(line.lineId(), line.lineName(), line.statuses().stream().map(status ->
                        new Status(status.severity(), status.description(), status.reason().orElse(null)))
                        .toList())).toList());
    }

    public record Line(String lineId, String lineName, List<Status> statuses) {
        public Line { statuses = List.copyOf(statuses); }
    }
    public record Status(int severity, String description, String reason) { }
}
