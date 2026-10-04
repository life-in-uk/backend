package info.lifeinuk.backend.places;

import java.util.List;

/**
 * Public V1 contract: normalized candidates in provider order with WGS84 coordinates for the Roads API.
 * No provider payload, identifiers beyond the stable OS ID, or grid coordinates are exposed.
 */
public record PlaceSearchResponse(String query, List<Place> places, String attribution) {
    public PlaceSearchResponse { places = List.copyOf(places); }

    /** {@code type} is one of: postcode, city, town, village, hamlet, suburb, settlement. */
    public record Place(String id, String label, String name, String type, String area, String region,
            String country, double latitude, double longitude) { }
}
