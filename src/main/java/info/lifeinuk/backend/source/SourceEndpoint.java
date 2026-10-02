package info.lifeinuk.backend.source;

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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/** Qualified acquisition policy only. No fetched data belongs here. */
@Entity
@Table(name = "source_endpoint")
public class SourceEndpoint {
    public static final String BANK_HOLIDAYS_KEY = "gov-uk-bank-holidays-json";
    public static final String BANK_HOLIDAYS_URL = "https://www.gov.uk/bank-holidays.json";
    public static final String ATTRIBUTION_REFERENCE = "https://www.gov.uk/bank-holidays";
    public static final int DAILY_POLL_SECONDS = 86_400;
    public static final String PENDING_POLICY = "Acquisition and retention are not approved until explicit qualification.";

    @Id
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false, updatable = false)
    private Source source;

    @NotBlank
    @Column(name = "endpoint_key", nullable = false, unique = true, updatable = false, length = 100)
    private String key;

    @NotBlank
    @Column(nullable = false, updatable = false, length = 300)
    private String url;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "qualification_status", nullable = false, length = 20)
    private QualificationStatus qualificationStatus;

    @Column(name = "qualification_record", columnDefinition = "text")
    private String qualificationRecord;

    @NotBlank
    @Column(name = "attribution_reference", nullable = false, length = 300)
    private String attributionReference;

    @NotBlank
    @Column(name = "use_retention_policy", nullable = false, columnDefinition = "text")
    private String useRetentionPolicy;

    @Column(nullable = false)
    private boolean enabled;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "calendar_scope", nullable = false, length = 20)
    private CalendarScope calendarScope;

    @Positive
    @Column(name = "poll_interval_seconds", nullable = false)
    private int pollIntervalSeconds;

    @NotNull
    @Column(name = "next_poll_at", nullable = false)
    private Instant nextPollAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected SourceEndpoint() { }

    public static SourceEndpoint bankHolidays(Source source, Instant nextPollAt) {
        Objects.requireNonNull(source, "Source is required");
        if (!Source.BANK_HOLIDAYS_KEY.equals(source.getKey())) {
            throw new IllegalArgumentException("The Bank Holidays endpoint requires its approved source identity");
        }
        SourceEndpoint endpoint = new SourceEndpoint();
        endpoint.id = UUID.randomUUID();
        endpoint.source = source;
        endpoint.key = BANK_HOLIDAYS_KEY;
        endpoint.url = BANK_HOLIDAYS_URL;
        endpoint.qualificationStatus = QualificationStatus.PENDING;
        endpoint.attributionReference = ATTRIBUTION_REFERENCE;
        endpoint.useRetentionPolicy = PENDING_POLICY;
        endpoint.enabled = true;
        endpoint.calendarScope = CalendarScope.CURRENT_YEAR;
        endpoint.pollIntervalSeconds = DAILY_POLL_SECONDS;
        endpoint.nextPollAt = Objects.requireNonNull(nextPollAt, "Next poll eligibility is required");
        return endpoint;
    }

    public void qualify(String record, String useRetentionPolicy) {
        if (record == null || record.isBlank() || useRetentionPolicy == null || useRetentionPolicy.isBlank()) {
            throw new IllegalArgumentException("Explicit qualification record and use/retention policy are required");
        }
        this.qualificationRecord = record;
        this.useRetentionPolicy = useRetentionPolicy;
        this.qualificationStatus = QualificationStatus.QUALIFIED;
    }

    /** Permission check only; this does not initiate acquisition or scheduling. */
    public boolean isQualifiedAndEnabled() {
        return source.isEnabled() && enabled && qualificationStatus == QualificationStatus.QUALIFIED
                && qualificationRecord != null && !qualificationRecord.isBlank();
    }

    public int calendarYear(Clock clock) {
        return LocalDate.now(clock.withZone(ZoneId.of("Europe/London"))).getYear();
    }

    public UUID getId() { return id; }
    public Source getSource() { return source; }
    public String getKey() { return key; }
    public String getUrl() { return url; }
    public QualificationStatus getQualificationStatus() { return qualificationStatus; }
    public String getQualificationRecord() { return qualificationRecord; }
    public String getAttributionReference() { return attributionReference; }
    public String getUseRetentionPolicy() { return useRetentionPolicy; }
    public boolean isEnabled() { return enabled; }
    public CalendarScope getCalendarScope() { return calendarScope; }
    public int getPollIntervalSeconds() { return pollIntervalSeconds; }
    public Instant getNextPollAt() { return nextPollAt; }
    public long getVersion() { return version; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setNextPollAt(Instant nextPollAt) {
        this.nextPollAt = Objects.requireNonNull(nextPollAt, "Next poll eligibility is required");
    }
}
