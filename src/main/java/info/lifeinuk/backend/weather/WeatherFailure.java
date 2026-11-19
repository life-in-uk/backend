package info.lifeinuk.backend.weather;

/** Bounded upstream outcome. Messages are fixed text: never the key, URL or provider body. */
final class WeatherFailure extends RuntimeException {
    enum Reason { NOT_CONFIGURED, UNAVAILABLE, INVALID_RESPONSE }
    private final Reason reason;

    WeatherFailure(Reason reason) {
        super(reason.name(), null, false, false);
        this.reason = reason;
    }

    Reason reason() { return reason; }
}
