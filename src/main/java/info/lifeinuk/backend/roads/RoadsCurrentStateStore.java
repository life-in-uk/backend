package info.lifeinuk.backend.roads;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import static info.lifeinuk.backend.roads.NationalHighwaysRoadClosuresInterpretation.*;

/** Only normalized facts are JSONB; metadata and facts change together in one locked row. */
@Repository
class RoadsCurrentStateStore {
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder()
            .addMixIn(Location.class, LocationType.class)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    // Persistence-only discriminant. Does not change #28 or the public API.
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "locationForm")
    @JsonSubTypes({@JsonSubTypes.Type(value = LinearLocation.class, name = "linear"),
            @JsonSubTypes.Type(value = LocationGroup.class, name = "group")})
    private abstract static class LocationType { }

    RoadsCurrentStateStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private String facts(RoadsCurrentState input) { return json.writeValueAsString(input.closures()); }

    boolean insertFirst(RoadsCurrentState input) {
        return jdbc.update("""
                INSERT INTO roads_current_snapshot VALUES (1,?,?,?,?,?,?,?::jsonb)
                ON CONFLICT (singleton) DO NOTHING
                """, input.ingestionRunId(), input.sourceEndpointId(), input.snapshotAt().getEpochSecond(),
                input.snapshotAt().getNano(), java.sql.Timestamp.from(input.projectedAt()), input.pageCount(), facts(input)) == 1;
    }

    Optional<RoadsCurrentState> read(boolean lock) {
        return jdbc.query("SELECT * FROM roads_current_snapshot WHERE singleton=1" + (lock ? " FOR UPDATE" : ""),
                (row, index) -> new RoadsCurrentState(row.getObject("ingestion_run_id", UUID.class),
                        row.getObject("source_endpoint_id", UUID.class),
                        Instant.ofEpochSecond(row.getLong("snapshot_epoch_second"), row.getInt("snapshot_nano")),
                        row.getTimestamp("projected_at").toInstant(), row.getInt("page_count"),
                        json.readValue(row.getString("closures"), new TypeReference<List<RoadsCurrentState.Record>>() { })))
                .stream().findFirst();
    }

    void replace(RoadsCurrentState input) {
        jdbc.update("""
                UPDATE roads_current_snapshot SET ingestion_run_id=?,source_endpoint_id=?,
                snapshot_epoch_second=?,snapshot_nano=?,projected_at=?,page_count=?,closures=?::jsonb WHERE singleton=1
                """, input.ingestionRunId(), input.sourceEndpointId(), input.snapshotAt().getEpochSecond(),
                input.snapshotAt().getNano(), java.sql.Timestamp.from(input.projectedAt()), input.pageCount(), facts(input));
    }
}
