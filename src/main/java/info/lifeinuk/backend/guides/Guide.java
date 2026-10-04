package info.lifeinuk.backend.guides;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Curated document aggregate. Publication is explicit; there is no editorial workflow. */
@Entity
@Table(name = "guide")
public class Guide {
    @Id private UUID id;
    @Column(nullable = false, unique = true, length = 160) private String slug;
    @Column(nullable = false, length = 100) private String category;
    @Column(nullable = false, length = 200) private String title;
    @Column(nullable = false, length = 1000) private String summary;
    @Column(nullable = false, columnDefinition = "text") private String content;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20) private GuideStatus status;
    @Column(name = "published_at") private Instant publishedAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @OneToMany(mappedBy = "guide", cascade = {CascadeType.PERSIST, CascadeType.MERGE}, orphanRemoval = true)
    @OrderBy("sourceOrder ASC")
    private List<GuideSource> sources = new ArrayList<>();

    protected Guide() { }

    public Guide(String slug, String category, String title, String summary, String content, Instant updatedAt) {
        this.id = UUID.randomUUID();
        this.slug = key(slug, 160);
        this.category = key(category, 100);
        this.title = text(title, 200);
        this.summary = text(summary, 1000);
        this.content = text(content, Integer.MAX_VALUE);
        this.updatedAt = Objects.requireNonNull(updatedAt, "Update time is required");
        this.status = GuideStatus.DRAFT;
    }

    public void addSource(String organisation, String title, String url, Instant accessedAt) {
        sources.add(new GuideSource(this, organisation, title, url, accessedAt, sources.size()));
    }

    public void publish(Instant at) {
        if (status != GuideStatus.DRAFT || at == null || at.isBefore(updatedAt)) {
            throw new IllegalArgumentException("Publication requires a draft and a time at or after its update");
        }
        status = GuideStatus.PUBLISHED;
        publishedAt = at;
        updatedAt = at;
    }

    /** Package-owned reviewed import operation, not a public editorial API. */
    void replaceContent(String category, String title, String summary, String content,
            GuideStatus status, Instant publishedAt, Instant updatedAt) {
        this.category = key(category, 100);
        this.title = text(title, 200);
        this.summary = text(summary, 1000);
        this.content = text(content, Integer.MAX_VALUE);
        validatePublication(status, publishedAt, updatedAt);
        this.status = status;
        this.publishedAt = publishedAt;
        this.updatedAt = updatedAt;
    }

    void clearSources() { sources.clear(); }

    static void validatePublication(GuideStatus status, Instant publishedAt, Instant updatedAt) {
        if (status == null || updatedAt == null || (status == GuideStatus.DRAFT && publishedAt != null)
                || (status == GuideStatus.PUBLISHED && (publishedAt == null || updatedAt.isBefore(publishedAt)))) {
            throw new IllegalArgumentException("DRAFT requires no publishedAt; PUBLISHED requires publishedAt <= updatedAt");
        }
    }

    static String key(String value, int max) {
        if (value == null || value.length() > max || !value.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("A lower-case hyphenated guide key is required");
        }
        return value;
    }
    static String text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException("Nonblank guide text within its field limit is required");
        }
        return value;
    }
    public UUID getId() { return id; }
    public String getSlug() { return slug; }
    public String getCategory() { return category; }
    public String getTitle() { return title; }
    public String getSummary() { return summary; }
    public String getContent() { return content; }
    public GuideStatus getStatus() { return status; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<GuideSource> getSources() { return List.copyOf(sources); }
}
