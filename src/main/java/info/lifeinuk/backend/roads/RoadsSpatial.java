package info.lifeinuk.backend.roads;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static info.lifeinuk.backend.roads.NationalHighwaysRoadClosuresInterpretation.*;

/** National Highways Road & Lane Closures only. Not a generic CRS resolver. */
final class RoadsSpatial {
    static final double RADIUS_METERS = 15_000;
    static final double EARTH_RADIUS_METERS = 6_371_008.8;
    record Point(double latitude, double longitude) { }
    record Component(LinearLocation source, List<Point> positions) {
        Component { positions = List.copyOf(positions); }
    }
    record Match(double distanceMeters, List<Component> components) {
        Match { components = List.copyOf(components); }
    }

    static void validateLocation(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Finite geographic latitude and longitude are required");
        }
    }

    static Optional<Match> match(List<Location> locations, double latitude, double longitude) {
        validateLocation(latitude, longitude);
        var components = new ArrayList<Component>();
        locations.forEach(location -> collect(location, components));
        if (components.isEmpty()) { return Optional.empty(); }
        double nearest = Double.POSITIVE_INFINITY;
        Point user = new Point(latitude, longitude);
        for (var component : components) {
            var points = component.positions();
            for (int index = 1; index < points.size(); index++) {
                nearest = Math.min(nearest, segmentDistance(user, points.get(index - 1), points.get(index)));
            }
        }
        return Double.isFinite(nearest) ? Optional.of(new Match(nearest, components)) : Optional.empty();
    }

    private static void collect(Location location, List<Component> components) {
        if (location instanceof LocationGroup group) {
            group.locations().forEach(member -> collect(member, components));
        } else if (location instanceof LinearLocation linear) {
            linear.geometry().flatMap(RoadsSpatial::positions).ifPresent(points -> components.add(new Component(linear, points)));
        }
    }

    private static Optional<List<Point>> positions(Geometry geometry) {
        // Owner-approved Issue #30 decision for this endpoint's observed representation:
        // literal ESPG::4326, dimension 2, first ordinate LATITUDE, second LONGITUDE.
        // Never infer axes from generic EPSG:4326 or silently correct the source CRS spelling.
        if (!"ESPG::4326".equals(geometry.srsName()) || geometry.dimension() != 2 || geometry.positions().size() < 2) {
            return Optional.empty();
        }
        var points = new ArrayList<Point>();
        for (var tuple : geometry.positions()) {
            if (tuple.size() != 2) { return Optional.empty(); }
            double latitude = tuple.getFirst().doubleValue(), longitude = tuple.get(1).doubleValue();
            try { validateLocation(latitude, longitude); }
            catch (IllegalArgumentException invalid) { return Optional.empty(); }
            points.add(new Point(latitude, longitude));
        }
        // Antipodal endpoints do not define a unique shortest arc. Do not invent a geometry.
        for (int index = 1; index < points.size(); index++) {
            if (angle(vector(points.get(index - 1)), vector(points.get(index))) >= Math.PI - 1e-10) {
                return Optional.empty();
            }
        }
        return Optional.of(List.copyOf(points));
    }

    /** Minimum distance to the minor great-circle arc, including both endpoints. Metres on a mean-radius sphere. */
    static double segmentDistance(Point user, Point start, Point end) {
        double[] p = vector(user), a = vector(start), b = vector(end);
        double ab = angle(a, b);
        double nearest = Math.min(angle(p, a), angle(p, b));
        double[] normal = cross(a, b);
        double length = norm(normal);
        if (length > 1e-12 && ab < Math.PI - 1e-10) {
            normal = scale(normal, 1 / length);
            double projection = dot(p, normal);
            double[] q = new double[]{p[0] - projection * normal[0], p[1] - projection * normal[1], p[2] - projection * normal[2]};
            if (norm(q) > 1e-12) {
                q = scale(q, 1 / norm(q));
                for (int sign : new int[]{1, -1}) {
                    double[] candidate = scale(q, sign);
                    if (angle(a, candidate) + angle(candidate, b) <= ab + 1e-10) {
                        nearest = Math.min(nearest, angle(p, candidate));
                    }
                }
            }
        }
        return nearest * EARTH_RADIUS_METERS;
    }

    private static double[] vector(Point point) {
        double lat = Math.toRadians(point.latitude()), lon = Math.toRadians(point.longitude());
        return new double[]{Math.cos(lat) * Math.cos(lon), Math.cos(lat) * Math.sin(lon), Math.sin(lat)};
    }
    private static double dot(double[] a, double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};
    }
    private static double norm(double[] a) { return Math.sqrt(dot(a,a)); }
    private static double[] scale(double[] a, double s) { return new double[]{a[0]*s,a[1]*s,a[2]*s}; }
    private static double angle(double[] a, double[] b) { return Math.atan2(norm(cross(a,b)), dot(a,b)); }
}
