package info.lifeinuk.backend.evidence;

import java.time.Instant;

/**
 * One provider response exactly as received, plus the request provenance needed to reconstruct it.
 * The query is the exact credential-free query string sent; credentials only ever travel in headers.
 */
public record CapturedResponse(byte[] payload, String mediaType, Instant requestedAt, Instant observedAt,
        int httpStatus, String requestQuery, int pageNumber) { }
