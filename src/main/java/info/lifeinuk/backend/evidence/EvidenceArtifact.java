package info.lifeinuk.backend.evidence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.hibernate.annotations.Immutable;

/** Exact observed bytes, with no parsing, normalization or payload deduplication. */
@Entity
@Immutable
@Table(name = "evidence_artifact")
public class EvidenceArtifact {
    private static final Pattern CREDENTIAL_PARAMETER =
            Pattern.compile("subscription-key|ocp-apim|api[-_]?key", Pattern.CASE_INSENSITIVE);
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ingestion_run_id", nullable = false, updatable = false)
    private IngestionRun ingestionRun;
    @Column(nullable = false, updatable = false, columnDefinition = "bytea")
    private byte[] payload;
    @Column(name = "media_type", nullable = false, updatable = false, length = 200)
    private String mediaType;
    @Column(name = "observed_at", nullable = false, updatable = false)
    private Instant observedAt;
    @Column(name = "sha256", nullable = false, updatable = false, length = 64)
    private String sha256;
    // Optional request provenance: all present or all absent (enforced here and by V6).
    @Column(name = "request_query", updatable = false, length = 2000)
    private String requestQuery;
    @Column(name = "page_number", updatable = false)
    private Integer pageNumber;
    @Column(name = "http_status", updatable = false)
    private Integer httpStatus;
    @Column(name = "requested_at", updatable = false)
    private Instant requestedAt;

    protected EvidenceArtifact() { }

    public EvidenceArtifact(IngestionRun run, byte[] payload, String mediaType, Instant observedAt) {
        this.ingestionRun = Objects.requireNonNull(run, "Ingestion run is required");
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("Non-empty raw payload is required");
        }
        if (mediaType == null || mediaType.isBlank() || mediaType.length() > 200) {
            throw new IllegalArgumentException("Media type is required (max 200)");
        }
        if (observedAt == null || observedAt.isBefore(run.getStartedAt())
                || (run.getCompletedAt() != null && observedAt.isAfter(run.getCompletedAt()))) {
            throw new IllegalArgumentException("Observation must be within the run interval");
        }
        this.id = UUID.randomUUID();
        this.payload = payload.clone();
        this.mediaType = mediaType;
        this.observedAt = observedAt;
        try {
            this.sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(this.payload));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java must provide SHA-256", impossible);
        }
    }

    /** A response captured with its request provenance; the payload remains provider-exact. */
    public EvidenceArtifact(IngestionRun run, CapturedResponse response) {
        this(run, Objects.requireNonNull(response, "Captured response is required").payload(),
                response.mediaType(), response.observedAt());
        String query = response.requestQuery();
        if (query == null || query.isBlank() || query.length() > 2000) {
            throw new IllegalArgumentException("Request query is required (max 2000)");
        }
        if (CREDENTIAL_PARAMETER.matcher(query).find()) {
            throw new IllegalArgumentException("Request query must not carry credentials");
        }
        if (response.pageNumber() < 1) {
            throw new IllegalArgumentException("Page number must be positive");
        }
        if (response.httpStatus() < 100 || response.httpStatus() > 599) {
            throw new IllegalArgumentException("HTTP status is invalid");
        }
        Instant requested = response.requestedAt();
        if (requested == null || requested.isAfter(observedAt) || requested.isBefore(run.getStartedAt())) {
            throw new IllegalArgumentException("Request must be within the run interval and precede observation");
        }
        this.requestQuery = query;
        this.pageNumber = response.pageNumber();
        this.httpStatus = response.httpStatus();
        this.requestedAt = requested;
    }

    public UUID getId() { return id; }
    public IngestionRun getIngestionRun() { return ingestionRun; }
    public byte[] getPayload() { return payload.clone(); }
    /** Exact size is derived from stored bytes, so it cannot drift from the payload. */
    public int getByteSize() { return payload.length; }
    public String getMediaType() { return mediaType; }
    public Instant getObservedAt() { return observedAt; }
    public String getSha256() { return sha256; }
    public String getRequestQuery() { return requestQuery; }
    public Integer getPageNumber() { return pageNumber; }
    public Integer getHttpStatus() { return httpStatus; }
    public Instant getRequestedAt() { return requestedAt; }
}
