package info.lifeinuk.backend.places;

/**
 * OSGB36 British National Grid easting/northing → WGS84 latitude/longitude, following Ordnance Survey's
 * "A guide to coordinate systems in Great Britain":
 * <ol>
 *   <li>exact inverse Transverse Mercator on the Airy 1830 ellipsoid (National Grid projection constants);</li>
 *   <li>OS-published 7-parameter Helmert transformation OSGB36 → WGS84 (inverse of the guide's
 *       ETRS89/WGS84 → OSGB36 parameters), which OS states is accurate to about 3.5 m (95%);</li>
 *   <li>exact cartesian → geodetic conversion on the GRS80/WGS84 ellipsoid.</li>
 * </ol>
 * OS's definitive OSTN15 grid transformation (~0.1 m) needs an external grid data file and is not used here.
 */
final class BritishNationalGrid {
    // Airy 1830 and National Grid true-origin constants.
    private static final double AIRY_A = 6_377_563.396, AIRY_B = 6_356_256.909;
    private static final double F0 = 0.9996012717, E0 = 400_000, N0 = -100_000;
    private static final double PHI0 = Math.toRadians(49), LAMBDA0 = Math.toRadians(-2);
    // GRS80 (WGS84 within the precision relevant here).
    private static final double GRS80_A = 6_378_137.000, GRS80_B = 6_356_752.3141;
    // OSGB36 → WGS84 Helmert: tx, ty, tz (m), scale (ppm), rx, ry, rz (arc-seconds).
    private static final double TX = 446.448, TY = -125.157, TZ = 542.060, S_PPM = -20.4894;
    private static final double RX = 0.1502, RY = 0.2470, RZ = 0.8421;
    /** National Grid coverage: 0–700 km east, 0–1300 km north. */
    static final double MAX_EASTING = 700_000, MAX_NORTHING = 1_300_000;

    record LatLon(double latitude, double longitude) { }

    private BritishNationalGrid() { }

    static boolean covers(double easting, double northing) {
        return Double.isFinite(easting) && Double.isFinite(northing)
                && easting >= 0 && easting <= MAX_EASTING && northing >= 0 && northing <= MAX_NORTHING;
    }

    static LatLon toWgs84(double easting, double northing) {
        if (!covers(easting, northing)) {
            throw new IllegalArgumentException("Coordinates are outside the British National Grid");
        }
        double[] osgb36 = osgb36(easting, northing);
        double[] cartesian = cartesian(osgb36[0], osgb36[1], AIRY_A, AIRY_B);
        double[] shifted = helmert(cartesian);
        return geodetic(shifted, GRS80_A, GRS80_B);
    }

    /** Inverse Transverse Mercator (OS guide, section C.2). Returns OSGB36 {latitude, longitude} in radians. */
    static double[] osgb36(double easting, double northing) {
        double a = AIRY_A, b = AIRY_B;
        double e2 = (a * a - b * b) / (a * a), n = (a - b) / (a + b);
        double phi = PHI0, m = 0;
        do {
            phi = (northing - N0 - m) / (a * F0) + phi;
            m = meridional(phi, b, n);
        } while (Math.abs(northing - N0 - m) >= 0.00001);
        double sin = Math.sin(phi), cos = Math.cos(phi), tan = Math.tan(phi);
        double nu = a * F0 / Math.sqrt(1 - e2 * sin * sin);
        double rho = a * F0 * (1 - e2) / Math.pow(1 - e2 * sin * sin, 1.5);
        double eta2 = nu / rho - 1;
        double tan2 = tan * tan, tan4 = tan2 * tan2, tan6 = tan4 * tan2, sec = 1 / cos;
        double vii = tan / (2 * rho * nu);
        double viii = tan / (24 * rho * Math.pow(nu, 3)) * (5 + 3 * tan2 + eta2 - 9 * tan2 * eta2);
        double ix = tan / (720 * rho * Math.pow(nu, 5)) * (61 + 90 * tan2 + 45 * tan4);
        double x = sec / nu;
        double xi = sec / (6 * Math.pow(nu, 3)) * (nu / rho + 2 * tan2);
        double xii = sec / (120 * Math.pow(nu, 5)) * (5 + 28 * tan2 + 24 * tan4);
        double xiia = sec / (5040 * Math.pow(nu, 7)) * (61 + 662 * tan2 + 1320 * tan4 + 720 * tan6);
        double de = easting - E0;
        double latitude = phi - vii * de * de + viii * Math.pow(de, 4) - ix * Math.pow(de, 6);
        double longitude = LAMBDA0 + x * de - xi * Math.pow(de, 3) + xii * Math.pow(de, 5) - xiia * Math.pow(de, 7);
        return new double[]{latitude, longitude};
    }

    private static double meridional(double phi, double b, double n) {
        double n2 = n * n, n3 = n2 * n, d = phi - PHI0, s = phi + PHI0;
        return b * F0 * ((1 + n + 5.0 / 4 * n2 + 5.0 / 4 * n3) * d
                - (3 * n + 3 * n2 + 21.0 / 8 * n3) * Math.sin(d) * Math.cos(s)
                + (15.0 / 8 * n2 + 15.0 / 8 * n3) * Math.sin(2 * d) * Math.cos(2 * s)
                - 35.0 / 24 * n3 * Math.sin(3 * d) * Math.cos(3 * s));
    }

    private static double[] cartesian(double latitude, double longitude, double a, double b) {
        double e2 = (a * a - b * b) / (a * a), sin = Math.sin(latitude);
        double nu = a / Math.sqrt(1 - e2 * sin * sin);
        return new double[]{nu * Math.cos(latitude) * Math.cos(longitude), nu * Math.cos(latitude) * Math.sin(longitude),
                (1 - e2) * nu * sin};
    }

    private static double[] helmert(double[] p) {
        double s = S_PPM * 1e-6, toRadians = Math.PI / (180 * 3600);
        double rx = RX * toRadians, ry = RY * toRadians, rz = RZ * toRadians;
        return new double[]{
                TX + (1 + s) * p[0] - rz * p[1] + ry * p[2],
                TY + rz * p[0] + (1 + s) * p[1] - rx * p[2],
                TZ - ry * p[0] + rx * p[1] + (1 + s) * p[2]};
    }

    private static LatLon geodetic(double[] p, double a, double b) {
        double e2 = (a * a - b * b) / (a * a), radius = Math.hypot(p[0], p[1]);
        double latitude = Math.atan2(p[2], radius * (1 - e2)), previous;
        do {
            previous = latitude;
            double nu = a / Math.sqrt(1 - e2 * Math.sin(latitude) * Math.sin(latitude));
            latitude = Math.atan2(p[2] + e2 * nu * Math.sin(latitude), radius);
        } while (Math.abs(latitude - previous) > 1e-14);
        return new LatLon(Math.toDegrees(latitude), Math.toDegrees(Math.atan2(p[1], p[0])));
    }
}
