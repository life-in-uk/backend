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
    public static final String TFL_UNDERGROUND_KEY = "tfl-underground-status";
    public static final String TFL_UNDERGROUND_URL = "https://api.tfl.gov.uk/Line/Mode/tube/Status";
    public static final String TFL_TERMS_REFERENCE = "https://tfl.gov.uk/corporate/terms-and-conditions/transport-data-service";
    public static final String TFL_USE_RETENTION_POLICY = """
            Intended Life in UK Travel Live use: Underground operational line status.
            Raw high-frequency evidence is short-lived and immutable while retained;
            default target retention is approximately 72 hours from EvidenceArtifact observation time,
            not filesystem, build or application creation time. Future Current State is independent
            of raw evidence expiry and remains until updated or expired under its own domain rules.
            Future meaningful normalized Change History has a separate, potentially long-term lifecycle;
            repeated unchanged polling observations must not become permanent business-history rows.
            TfL licence/terms override this product default, including retention/republication requirements.
            Future reuse must satisfy TfL attribution, third-party acknowledgements, branding and rate limits;
            see https://tfl.gov.uk/corporate/terms-and-conditions/transport-data-service.
            This is policy only: no acquisition, current state, change history or cleanup is implemented.
            """;
    public static final String NATIONAL_HIGHWAYS_ROADS_BASE_URL = "https://api.data.nationalhighways.co.uk/roads/v2.0";
    public static final String NATIONAL_HIGHWAYS_ROAD_CLOSURES_KEY = "national-highways-road-closures";
    public static final String NATIONAL_HIGHWAYS_ROAD_CLOSURES_URL = NATIONAL_HIGHWAYS_ROADS_BASE_URL + "/closures";
    public static final String NATIONAL_HIGHWAYS_REFERENCE = "https://developer.data.nationalhighways.co.uk/terms";
    public static final String NATIONAL_HIGHWAYS_USE_RETENTION_POLICY = """
            Intended Life in UK Travel Live use: road and lane closures on the National Highways network.
            Official contract: Road and Lane Closures Data Service (DATEX II) v2.0, GET /closures; planned and
            unplanned closures are query modes of this one endpoint, requested as JSON DATEX II.
            The subscription key is external configuration only: never persisted, logged, returned or placed in a URL.
            No raw-evidence retention duration, polling frequency, republication or attribution wording is approved
            by this draft. National Highways licence/terms, attribution and rate limits override product defaults
            and must be reviewed and recorded by the owner before qualification.
            This is policy only: no acquisition, parsing, current state, change history or cleanup is implemented.
            """;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "calendar_scope", length = 20)
    private CalendarScope calendarScope;

    @Positive
    @Column(name = "poll_interval_seconds")
    private Integer pollIntervalSeconds;

    @Column(name = "next_poll_at")
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

    /** No calendar or polling policy is invented for the operational-status foundation. */
    public static SourceEndpoint tflUnderground(Source source) {
        Objects.requireNonNull(source, "Source is required");
        if (!Source.TFL_KEY.equals(source.getKey()) || !Source.TFL_UNDERGROUND_SCOPE.equals(source.getScope())) {
            throw new IllegalArgumentException("Underground status requires its canonical TfL source and scope");
        }
        SourceEndpoint endpoint = new SourceEndpoint();
        endpoint.id = UUID.randomUUID();
        endpoint.source = source;
        endpoint.key = TFL_UNDERGROUND_KEY;
        endpoint.url = TFL_UNDERGROUND_URL;
        endpoint.qualificationStatus = QualificationStatus.PENDING;
        endpoint.attributionReference = TFL_TERMS_REFERENCE;
        endpoint.useRetentionPolicy = PENDING_POLICY + "\n" + TFL_USE_RETENTION_POLICY;
        endpoint.enabled = true;
        return endpoint;
    }

    /** Road and Lane Closures v2: one endpoint for all closure query modes; no calendar or polling policy is invented. */
    public static SourceEndpoint nationalHighwaysRoadClosures(Source source) {
        Objects.requireNonNull(source, "Source is required");
        if (!Source.NATIONAL_HIGHWAYS_KEY.equals(source.getKey())
                || !Source.NATIONAL_HIGHWAYS_ROAD_CLOSURES_SCOPE.equals(source.getScope())) {
            throw new IllegalArgumentException("Road closures require the canonical National Highways source and scope");
        }
        SourceEndpoint endpoint = new SourceEndpoint();
        endpoint.id = UUID.randomUUID();
        endpoint.source = source;
        endpoint.key = NATIONAL_HIGHWAYS_ROAD_CLOSURES_KEY;
        endpoint.url = NATIONAL_HIGHWAYS_ROAD_CLOSURES_URL;
        endpoint.qualificationStatus = QualificationStatus.PENDING;
        endpoint.attributionReference = NATIONAL_HIGHWAYS_REFERENCE;
        endpoint.useRetentionPolicy = PENDING_POLICY + "\n" + NATIONAL_HIGHWAYS_USE_RETENTION_POLICY;
        endpoint.enabled = true;
        return endpoint;
    }

    boolean hasCanonicalTflIdentity() {
        return TFL_UNDERGROUND_KEY.equals(key) && TFL_UNDERGROUND_URL.equals(url)
                && Source.TFL_KEY.equals(source.getKey())
                && Source.TFL_UNDERGROUND_SCOPE.equals(source.getScope())
                && calendarScope == null && pollIntervalSeconds == null && nextPollAt == null;
    }

    boolean hasCanonicalNationalHighwaysIdentity() {
        return NATIONAL_HIGHWAYS_ROAD_CLOSURES_KEY.equals(key) && NATIONAL_HIGHWAYS_ROAD_CLOSURES_URL.equals(url)
                && Source.NATIONAL_HIGHWAYS_KEY.equals(source.getKey())
                && Source.NATIONAL_HIGHWAYS_ROAD_CLOSURES_SCOPE.equals(source.getScope())
                && calendarScope == null && pollIntervalSeconds == null && nextPollAt == null;
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
                && qualificationRecord != null && !qualificationRecord.isBlank()
                && useRetentionPolicy != null && !useRetentionPolicy.isBlank()
                && (!(TFL_UNDERGROUND_KEY.equals(key) || Source.TFL_KEY.equals(source.getKey()))
                    || hasCanonicalTflIdentity())
                && (!(NATIONAL_HIGHWAYS_ROAD_CLOSURES_KEY.equals(key) || Source.NATIONAL_HIGHWAYS_KEY.equals(source.getKey()))
                    || hasCanonicalNationalHighwaysIdentity());
    }

    public int calendarYear(Clock clock) {
        if (calendarScope != CalendarScope.CURRENT_YEAR) {
            throw new IllegalStateException("This endpoint has no calendar policy");
        }
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
    public Integer getPollIntervalSeconds() { return pollIntervalSeconds; }
    public Instant getNextPollAt() { return nextPollAt; }
    public long getVersion() { return version; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setNextPollAt(Instant nextPollAt) {
        this.nextPollAt = Objects.requireNonNull(nextPollAt, "Next poll eligibility is required");
    }
}
