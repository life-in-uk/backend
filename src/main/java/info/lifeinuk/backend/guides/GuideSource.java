package info.lifeinuk.backend.guides;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Editorial reference metadata only. URLs are never fetched by guide reads. */
@Entity
@Table(name = "guide_source")
public class GuideSource {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "guide_id", nullable = false) private Guide guide;
    @Column(name = "editorial_key", length = 160) private String key;
    @Column(nullable = false, length = 200) private String organisation;
    @Column(nullable = false, length = 300) private String title;
    @Column(nullable = false, length = 2000) private String url;
    @Column(name = "accessed_at", nullable = false) private Instant accessedAt;
    @Column(name = "source_order", nullable = false) private int sourceOrder;

    protected GuideSource() { }
    GuideSource(Guide guide, String organisation, String title, String url, Instant accessedAt, int sourceOrder) {
        this(guide, null, organisation, title, url, accessedAt, sourceOrder);
    }
    GuideSource(Guide guide, String key, String organisation, String title, String url, Instant accessedAt, int sourceOrder) {
        this.id = UUID.randomUUID();
        this.key = key == null ? null : Guide.key(key, 160);
        this.guide = Objects.requireNonNull(guide);
        this.organisation = Guide.text(organisation, 200);
        this.title = Guide.text(title, 300);
        this.url = Guide.text(url, 2000);
        this.accessedAt = Objects.requireNonNull(accessedAt, "Source access time is required");
        this.sourceOrder = sourceOrder;
    }
    public String getKey() { return key; }
    public UUID getId() { return id; }
    public String getOrganisation() { return organisation; }
    public String getTitle() { return title; }
    public String getUrl() { return url; }
    public Instant getAccessedAt() { return accessedAt; }
}
