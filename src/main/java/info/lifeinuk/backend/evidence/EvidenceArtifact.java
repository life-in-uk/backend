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
import org.hibernate.annotations.Immutable;

/** Exact observed bytes, with no parsing, normalization or payload deduplication. */
@Entity
@Immutable
@Table(name = "evidence_artifact")
public class EvidenceArtifact {
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

    public UUID getId() { return id; }
    public IngestionRun getIngestionRun() { return ingestionRun; }
    public byte[] getPayload() { return payload.clone(); }
    public String getMediaType() { return mediaType; }
    public Instant getObservedAt() { return observedAt; }
    public String getSha256() { return sha256; }
}
