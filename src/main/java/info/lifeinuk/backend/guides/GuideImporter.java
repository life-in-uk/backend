package info.lifeinuk.backend.guides;

import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Exactly one validated guide per transaction. The slug and existing Guide UUID remain stable. */
@Service
class GuideImporter {
    enum Outcome { CREATED, UPDATED, UNCHANGED }
    record Result(UUID guideId, String slug, Outcome outcome) { }
    private final GuideRepository guides;
    GuideImporter(GuideRepository guides) { this.guides = guides; }

    @Transactional
    public Result apply(GuideImportDefinition input) {
        Objects.requireNonNull(input, "Validated definition is required");
        var existing = guides.findBySlug(input.slug());
        Guide guide = existing.orElseGet(() -> new Guide(input.slug(), input.category(), input.title(),
                input.summary(), input.content(), input.updatedAt()));
        if (input.evidence() == null && !guide.getEvidence().isEmpty()) {
            throw new IllegalArgumentException("Existing evidence requires an explicit evidence collection; use [] for deliberate removal");
        }
        var evidence = input.evidence() == null ? java.util.List.<GuideImportDefinition.Evidence>of() : input.evidence();
        boolean sameEvidence = evidenceDefinition(guide).equals(evidence);
        boolean sameSources = sourcesMatch(guide, input);
        if (existing.isPresent() && sameSources && sameEvidence && guide.getCategory().equals(input.category())
                && guide.getTitle().equals(input.title()) && guide.getSummary().equals(input.summary())
                && guide.getContent().equals(input.content()) && guide.getStatus() == input.status()
                && Objects.equals(guide.getPublishedAt(), input.publishedAt()) && guide.getUpdatedAt().equals(input.updatedAt())) {
            return new Result(guide.getId(), guide.getSlug(), Outcome.UNCHANGED);
        }
        guide.replaceContent(input.category(), input.title(), input.summary(), input.content(),
                input.status(), input.publishedAt(), input.updatedAt());
        // Links must be deleted before replacing their referenced source rows.
        if (!sameSources || !sameEvidence) {
            guide.clearEvidence();
            if (existing.isPresent()) { guides.flush(); }
        }
        if (!sameSources) {
            guide.clearSources();
            // Delete old owned rows before reusing their unique editorial positions. Still one transaction.
            if (existing.isPresent()) { guides.flush(); }
            for (var source : input.sources()) {
                guide.addSource(source);
            }
        }
        // Persist source keys before inserting links with composite foreign keys.
        if (!sameSources) { guides.saveAndFlush(guide); }
        if (!sameSources || !sameEvidence) {
            for (var item : evidence) { guide.addEvidence(item); }
        }
        guide = guides.saveAndFlush(guide);
        return new Result(guide.getId(), guide.getSlug(), existing.isPresent() ? Outcome.UPDATED : Outcome.CREATED);
    }

    private static java.util.List<GuideImportDefinition.Evidence> evidenceDefinition(Guide guide) {
        return guide.getEvidence().stream().map(item -> new GuideImportDefinition.Evidence(item.getKey(), item.getStatement(),
                item.getSupports().stream().map(support -> new GuideImportDefinition.Support(support.getSourceKey(),
                        support.getLocator(), support.getExcerpt(), support.getNote())).toList())).toList();
    }
    private static boolean sourcesMatch(Guide guide, GuideImportDefinition input) {
        return guide.getSources().stream().map(source -> new GuideImportDefinition.Source(source.getKey(), source.getOrganisation(),
                source.getTitle(), source.getUrl(), source.getAccessedAt())).toList().equals(input.sources());
    }
}
