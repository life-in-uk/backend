package info.lifeinuk.backend.guides;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Human-reviewed proposition and its ordered authoritative support. */
@Entity
@Table(name = "guide_evidence")
public class GuideEvidence {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "guide_id", nullable = false) private Guide guide;
    @Column(name = "editorial_key", nullable = false, length = 160) private String key;
    @Column(nullable = false, columnDefinition = "text") private String statement;
    @Column(name = "evidence_order", nullable = false) private int evidenceOrder;
    @OneToMany(mappedBy = "evidence", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("supportOrder ASC") private List<GuideEvidenceSupport> supports = new ArrayList<>();
    protected GuideEvidence() { }
    GuideEvidence(Guide guide, GuideImportDefinition.Evidence input, int order) {
        this.id = UUID.randomUUID(); this.guide = guide;
        this.key = input.key(); this.statement = input.statement(); this.evidenceOrder = order;
        for (var support : input.supports()) {
            supports.add(new GuideEvidenceSupport(this, guide.getId(), support, supports.size()));
        }
    }
    public String getKey() { return key; }
    public String getStatement() { return statement; }
    public List<GuideEvidenceSupport> getSupports() { return List.copyOf(supports); }
}
