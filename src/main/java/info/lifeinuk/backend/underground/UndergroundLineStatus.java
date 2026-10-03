package info.lifeinuk.backend.underground;

import java.util.List;

/** One source line with all its statuses in source order. */
public record UndergroundLineStatus(String lineId, String lineName, List<UndergroundOperationalStatus> statuses) {
    public UndergroundLineStatus {
        if (lineId == null || lineId.isBlank() || lineName == null || lineName.isBlank()) {
            throw new IllegalArgumentException("Nonblank line identity and name are required");
        }
        statuses = List.copyOf(statuses);
    }
}
