package info.lifeinuk.backend.acquisition;

/** Deliberately bounded, non-sensitive acquisition failure details. */
final class AcquisitionFailure extends RuntimeException {
    private final String code;

    AcquisitionFailure(String code, String message) {
        super(message);
        this.code = code;
    }

    String code() { return code; }
}
