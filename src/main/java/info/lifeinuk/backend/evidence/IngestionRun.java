package info.lifeinuk.backend.evidence;

import info.lifeinuk.backend.source.SourceEndpoint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One acquisition attempt; only STARTED may transition, once, to a terminal outcome. */
@Entity
@Table(name = "ingestion_run")
public class IngestionRun {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_endpoint_id", nullable = false, updatable = false)
    private SourceEndpoint sourceEndpoint;
    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20) private RunStatus status;
    @Column(name = "failure_code", length = 100) private String failureCode;
    @Column(name = "failure_message", length = 1000) private String failureMessage;
    @Version @Column(nullable = false) private long version;

    protected IngestionRun() { }

    public static IngestionRun start(SourceEndpoint endpoint, Instant startedAt) {
        Objects.requireNonNull(endpoint, "Source endpoint is required");
        if (!endpoint.isQualifiedAndEnabled()) {
            throw new IllegalStateException("Source endpoint must be qualified and enabled");
        }
        IngestionRun run = new IngestionRun();
        run.id = UUID.randomUUID();
        run.sourceEndpoint = endpoint;
        run.startedAt = Objects.requireNonNull(startedAt, "Start time is required");
        run.status = RunStatus.STARTED;
        return run;
    }

    public void succeed(Instant completedAt) {
        validateCompletion(completedAt);
        this.completedAt = completedAt;
        status = RunStatus.SUCCESS;
    }

    public void fail(Instant completedAt, String code, String message) {
        validateCompletion(completedAt);
        if (code == null || code.isBlank() || code.length() > 100
                || message == null || message.isBlank() || message.length() > 1000) {
            throw new IllegalArgumentException("Failure requires a code (max 100) and message (max 1000)");
        }
        this.completedAt = completedAt;
        failureCode = code;
        failureMessage = message;
        status = RunStatus.FAILED;
    }

    private void validateCompletion(Instant completion) {
        if (status != RunStatus.STARTED) {
            throw new IllegalStateException("A completed run cannot transition again");
        }
        if (completion == null || completion.isBefore(startedAt)) {
            throw new IllegalArgumentException("Completion must be at or after start");
        }
    }

    public UUID getId() { return id; }
    public SourceEndpoint getSourceEndpoint() { return sourceEndpoint; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public RunStatus getStatus() { return status; }
    public String getFailureCode() { return failureCode; }
    public String getFailureMessage() { return failureMessage; }
}
