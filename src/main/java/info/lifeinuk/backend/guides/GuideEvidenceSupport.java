package info.lifeinuk.backend.guides;

import jakarta.persistence.*;
import java.util.UUID;

/** Locator, verbatim excerpt and editorial explanation belong to this specific link. */
@Entity
@Table(name = "guide_evidence_support")
public class GuideEvidenceSupport {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "evidence_id", nullable = false) private GuideEvidence evidence;
    @Column(name = "guide_id", nullable = false) private UUID guideId;
    @Column(name = "source_key", nullable = false, length = 160) private String sourceKey;
    @Column(name = "support_order", nullable = false) private int supportOrder;
    @Column(columnDefinition = "text") private String locator;
    @Column(columnDefinition = "text") private String excerpt;
    @Column(nullable = false, columnDefinition = "text") private String note;
    protected GuideEvidenceSupport() { }
    GuideEvidenceSupport(GuideEvidence evidence, UUID guideId, GuideImportDefinition.Support input, int order) {
        this.id = UUID.randomUUID(); this.evidence = evidence; this.guideId = guideId;
        this.sourceKey = input.sourceKey(); this.supportOrder = order;
        this.locator = input.locator(); this.excerpt = input.excerpt(); this.note = input.note();
    }
    public String getSourceKey() { return sourceKey; }
    public String getLocator() { return locator; }
    public String getExcerpt() { return excerpt; }
    public String getNote() { return note; }
}
