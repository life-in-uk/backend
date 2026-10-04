package info.lifeinuk.backend.places;

/** Bounded upstream outcome. Messages are fixed text: never the query, key, URL or provider body. */
final class PlaceSearchFailure extends RuntimeException {
    enum Reason { NOT_CONFIGURED, UNAVAILABLE, INVALID_RESPONSE }
    private final Reason reason;

    PlaceSearchFailure(Reason reason) {
        super(reason.name(), null, false, false);
        this.reason = reason;
    }

    Reason reason() { return reason; }
}
