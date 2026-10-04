package info.lifeinuk.backend.roads;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static info.lifeinuk.backend.roads.NationalHighwaysRoadClosuresInterpretation.*;
import static org.assertj.core.api.Assertions.*;

class RoadsSpatialTest {
    static LinearLocation line(String crs, String... coordinates) {
        var points = java.util.Arrays.stream(coordinates).map(value -> java.util.Arrays.stream(value.split(","))
                .map(BigDecimal::new).toList()).toList();
        return new LinearLocation(Optional.of(new Geometry(crs, 2, "Source position text", points)),
                Optional.of("M1 northbound between J15 and J15A"), List.of(), List.of());
    }

    @Test
    void ownerApprovedM1CoordinatesAreLatitudeThenLongitudeWithoutCorrectingSourceCrs() {
        var geometry = line("ESPG::4326", "52.193516,-0.908380", "52.193682,-0.908629");
        var match = RoadsSpatial.match(List.of(geometry), 52.193516, -0.908380).orElseThrow();
        assertThat(match.distanceMeters()).isCloseTo(0, within(0.000001));
        assertThat(match.components().getFirst().positions().getFirst()).isEqualTo(new RoadsSpatial.Point(52.193516,-0.908380));
        assertThat(geometry.geometry().orElseThrow().srsName()).isEqualTo("ESPG::4326");
        assertThat(geometry.description()).contains("M1 northbound between J15 and J15A");
        assertThat(RoadsSpatial.match(List.of(geometry), -0.908380, 52.193516).orElseThrow().distanceMeters()).isGreaterThan(5_000_000);
        assertThat(RoadsSpatial.match(List.of(line("ESPG::4326", "-0.908380,52.193516", "-0.908629,52.193682")),
                52.193516,-0.908380).orElseThrow().distanceMeters()).isGreaterThan(5_000_000);
        assertThat(RoadsSpatial.match(List.of(line("EPSG:4326", "52.193516,-0.908380", "52.193682,-0.908629")),
                52.193516,-0.908380)).isEmpty();
    }

    @Test
    void distanceUsesEverySegmentIncludingInteriorRatherThanFirstOrNearestVertex() {
        var geometry = line("ESPG::4326", "0,-2", "0,0", "0,2");
        var match = RoadsSpatial.match(List.of(geometry), 0, 1).orElseThrow();
        assertThat(match.distanceMeters()).isCloseTo(0, within(0.00001));
        assertThat(RoadsSpatial.match(List.of(geometry), 1, 1).orElseThrow().distanceMeters())
                .isCloseTo(Math.PI / 180 * RoadsSpatial.EARTH_RADIUS_METERS, within(0.0001));
        assertThat(RoadsSpatial.match(List.of(geometry), 0, 3).orElseThrow().distanceMeters())
                .isCloseTo(Math.PI / 180 * RoadsSpatial.EARTH_RADIUS_METERS, within(0.0001));
        assertThat(RoadsSpatial.match(List.of(geometry), 1, 1)).isEqualTo(RoadsSpatial.match(List.of(geometry), 1, 1));
    }

    @Test
    void groupedRepeatedGeometriesSurviveAndAllComponentsParticipate() {
        var far = line("ESPG::4326", "0,0", "0,1");
        var near = line("ESPG::4326", "52.193516,-0.908380", "52.193682,-0.908629");
        var group = new LocationGroup(List.of(far,new LocationGroup(List.of(near,near))));
        var match = RoadsSpatial.match(List.of(group),52.193516,-0.908380).orElseThrow();
        assertThat(match.distanceMeters()).isCloseTo(0,within(0.00001));
        assertThat(match.components()).hasSize(3);
        assertThat(match.components().get(1)).isEqualTo(match.components().get(2));
    }

    @Test
    void missingInvalidAndAmbiguousGeometryHasNoFabricatedRelevanceFromDescription() {
        var textOnly = new LinearLocation(Optional.empty(),Optional.of("M1 near your location"),List.of(),List.of());
        assertThat(RoadsSpatial.match(List.of(textOnly),52,-1)).isEmpty();
        assertThat(RoadsSpatial.match(List.of(line("ESPG::4326","91,0","92,0")),52,-1)).isEmpty();
        assertThat(RoadsSpatial.match(List.of(line("ESPG::4326","0,0","0,180")),52,-1)).isEmpty();
        assertThat(RoadsSpatial.match(List.of(line("ESPG::4326","52,-1","52,-1")),52,-1).orElseThrow().distanceMeters()).isZero();
    }

    @Test
    void inclusiveFifteenKilometreRuleHasKnownInsideAndOutsideBoundary() {
        var equator = line("ESPG::4326","0,-1","0,1");
        double latitude = Math.toDegrees(RoadsSpatial.RADIUS_METERS / RoadsSpatial.EARTH_RADIUS_METERS);
        assertThat(RoadsSpatial.match(List.of(equator),latitude,0).orElseThrow().distanceMeters())
                .isCloseTo(15_000,within(0.000001));
        assertThat(RoadsSpatial.match(List.of(equator),latitude-0.000001,0).orElseThrow().distanceMeters()).isLessThan(15_000);
        assertThat(RoadsSpatial.match(List.of(equator),latitude+0.000001,0).orElseThrow().distanceMeters()).isGreaterThan(15_000);
    }
}
