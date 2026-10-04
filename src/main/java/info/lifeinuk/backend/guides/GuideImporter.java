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
        boolean sameSources = sourcesMatch(guide, input);
        if (existing.isPresent() && sameSources && guide.getCategory().equals(input.category())
                && guide.getTitle().equals(input.title()) && guide.getSummary().equals(input.summary())
                && guide.getContent().equals(input.content()) && guide.getStatus() == input.status()
                && Objects.equals(guide.getPublishedAt(), input.publishedAt()) && guide.getUpdatedAt().equals(input.updatedAt())) {
            return new Result(guide.getId(), guide.getSlug(), Outcome.UNCHANGED);
        }
        guide.replaceContent(input.category(), input.title(), input.summary(), input.content(),
                input.status(), input.publishedAt(), input.updatedAt());
        if (!sameSources) {
            guide.clearSources();
            // Delete old owned rows before reusing their unique editorial positions. Still one transaction.
            if (existing.isPresent()) { guides.flush(); }
            for (var source : input.sources()) {
                guide.addSource(source.organisation(), source.title(), source.url(), source.accessedAt());
            }
        }
        guide = guides.saveAndFlush(guide);
        return new Result(guide.getId(), guide.getSlug(), existing.isPresent() ? Outcome.UPDATED : Outcome.CREATED);
    }

    private static boolean sourcesMatch(Guide guide, GuideImportDefinition input) {
        return guide.getSources().stream().map(source -> new GuideImportDefinition.Source(source.getOrganisation(),
                source.getTitle(), source.getUrl(), source.getAccessedAt())).toList().equals(input.sources());
    }
}
