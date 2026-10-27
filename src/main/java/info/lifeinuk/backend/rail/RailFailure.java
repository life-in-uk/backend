package info.lifeinuk.backend.rail;

final class RailFailure extends RuntimeException {
    enum Reason { NOT_CONFIGURED, AUTHENTICATION, RATE_LIMIT, UPSTREAM_REQUEST, UPSTREAM_ERROR, TIMEOUT, NETWORK, INVALID_RESPONSE }
    final Reason reason;
    RailFailure(Reason reason) { super(reason.name(), null, false, false); this.reason = reason; }
}
