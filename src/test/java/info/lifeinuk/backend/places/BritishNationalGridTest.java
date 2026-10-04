package info.lifeinuk.backend.places;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class BritishNationalGridTest {
    private static double dms(int degrees, int minutes, double seconds) { return degrees + minutes / 60.0 + seconds / 3600.0; }

    /** Metres between two WGS84 points (small separations; spherical mean radius). */
    private static double metres(double lat1, double lon1, double lat2, double lon2) {
        double y = Math.toRadians(lat2 - lat1), x = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        return Math.hypot(x, y) * 6_371_008.8;
    }

    @Test
    void inverseTransverseMercatorMatchesTheOrdnanceSurveyWorkedExample() {
        // OS "A guide to coordinate systems in Great Britain", annex C worked example (Caister Water Tower):
        // E 651409.903, N 313177.270 -> OSGB36 52°39'27.2531"N, 1°43'4.5177"E.
        double[] osgb36 = BritishNationalGrid.osgb36(651_409.903, 313_177.270);
        assertThat(Math.toDegrees(osgb36[0])).isCloseTo(dms(52, 39, 27.2531), within(0.00005 / 3600));
        assertThat(Math.toDegrees(osgb36[1])).isCloseTo(dms(1, 43, 4.5177), within(0.00005 / 3600));
    }

    @Test
    void helmertDatumShiftLandsWithinTheOsStatedAccuracyOfTheEtrs89Position() {
        // Same point's ETRS89 (≈ WGS84) position per OS: 52°39'28.8282"N, 1°42'57.8663"E. OS states the Helmert
        // method is good to about 3.5 m (95%); OSTN15 would be needed for sub-metre agreement.
        var wgs84 = BritishNationalGrid.toWgs84(651_409.903, 313_177.270);
        assertThat(metres(wgs84.latitude(), wgs84.longitude(), dms(52, 39, 28.8282), dms(1, 42, 57.8663))).isLessThan(5);
    }

    @ParameterizedTest
    @CsvSource({"-1,100", "100,-1", "700001,100", "100,1300001", "NaN,100", "Infinity,100"})
    void coordinatesOutsideTheNationalGridAreRejected(double easting, double northing) {
        assertThat(BritishNationalGrid.covers(easting, northing)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> BritishNationalGrid.toWgs84(easting, northing));
    }

    @Test
    void eastingAndNorthingAreNeverConfusedWithLatitudeAndLongitude() {
        // Sheffield-area grid point: north of 53°N and just west of the Greenwich meridian.
        var sheffield = BritishNationalGrid.toWgs84(435_000, 387_000);
        assertThat(sheffield.latitude()).isBetween(53.3, 53.5);
        assertThat(sheffield.longitude()).isBetween(-1.6, -1.3);
        // Moving east increases longitude; moving north increases latitude.
        assertThat(BritishNationalGrid.toWgs84(436_000, 387_000).longitude()).isGreaterThan(sheffield.longitude());
        assertThat(BritishNationalGrid.toWgs84(435_000, 388_000).latitude()).isGreaterThan(sheffield.latitude());
        // Deterministic: identical input, identical output.
        assertThat(BritishNationalGrid.toWgs84(435_000, 387_000)).isEqualTo(sheffield);
    }
}
