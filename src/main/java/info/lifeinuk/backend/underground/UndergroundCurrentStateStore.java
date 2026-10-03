package info.lifeinuk.backend.underground;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Concrete relational snapshot persistence; writes require the projector's transaction/row lock. */
@Repository
class UndergroundCurrentStateStore {
    private final JdbcTemplate jdbc;

    UndergroundCurrentStateStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    boolean insertFirst(UndergroundStatusInterpretation input) {
        return jdbc.update("""
                INSERT INTO underground_current_snapshot
                    (singleton, evidence_artifact_id, observed_epoch_second, observed_nano)
                VALUES (1, ?, ?, ?) ON CONFLICT (singleton) DO NOTHING
                """, input.evidenceArtifactId(), input.observedAt().getEpochSecond(), input.observedAt().getNano()) == 1;
    }

    UndergroundCurrentState lock() {
        return header(true).orElseThrow();
    }

    Optional<UndergroundCurrentState> read() {
        return header(false).map(this::withLines);
    }

    UndergroundCurrentState withLines(UndergroundCurrentState header) {
        var lines = jdbc.query("SELECT line_id, line_name FROM underground_current_line ORDER BY line_order",
                (row, index) -> new UndergroundLineStatus(row.getString("line_id"), row.getString("line_name"),
                        jdbc.query("""
                                SELECT severity, description, reason FROM underground_current_status
                                WHERE line_id=? ORDER BY status_order
                                """, (status, position) -> new UndergroundOperationalStatus(status.getInt("severity"),
                                status.getString("description"), Optional.ofNullable(status.getString("reason"))),
                                row.getString("line_id"))));
        return new UndergroundCurrentState(header.evidenceArtifactId(), header.observedAt(), lines);
    }

    private Optional<UndergroundCurrentState> header(boolean locked) {
        return jdbc.query("SELECT evidence_artifact_id, observed_epoch_second, observed_nano FROM underground_current_snapshot WHERE singleton=1"
                        + (locked ? " FOR UPDATE" : ""),
                (row, index) -> new UndergroundCurrentState(row.getObject("evidence_artifact_id", UUID.class),
                        Instant.ofEpochSecond(row.getLong("observed_epoch_second"), row.getInt("observed_nano")), java.util.List.of()))
                .stream().findFirst();
    }

    void replace(UndergroundStatusInterpretation input) {
        jdbc.update("DELETE FROM underground_current_line"); // Only superseded Current State; never raw evidence.
        for (int lineOrder = 0; lineOrder < input.lines().size(); lineOrder++) {
            var line = input.lines().get(lineOrder);
            jdbc.update("INSERT INTO underground_current_line (line_id, line_name, line_order) VALUES (?, ?, ?)",
                    line.lineId(), line.lineName(), lineOrder);
            for (int statusOrder = 0; statusOrder < line.statuses().size(); statusOrder++) {
                var status = line.statuses().get(statusOrder);
                jdbc.update("""
                        INSERT INTO underground_current_status (line_id, status_order, severity, description, reason)
                        VALUES (?, ?, ?, ?, ?)
                        """, line.lineId(), statusOrder, status.severity(), status.description(), status.reason().orElse(null));
            }
        }
        jdbc.update("""
                UPDATE underground_current_snapshot
                SET evidence_artifact_id=?, observed_epoch_second=?, observed_nano=? WHERE singleton=1
                """, input.evidenceArtifactId(), input.observedAt().getEpochSecond(), input.observedAt().getNano());
    }
}
