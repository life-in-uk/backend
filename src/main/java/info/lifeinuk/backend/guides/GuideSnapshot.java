package info.lifeinuk.backend.guides;

/** Exact public projection as the validated Guide import model. Must be read inside a transaction. */
final class GuideSnapshot {
    private GuideSnapshot() { }

    static GuideImportDefinition from(Guide guide) {
        return new GuideImportDefinition(guide.getSlug(), guide.getCategory(), guide.getTitle(), guide.getSummary(),
                guide.getContent(), guide.getStatus(), guide.getPublishedAt(), guide.getUpdatedAt(),
                guide.getSources().stream().map(source -> new GuideImportDefinition.Source(source.getKey(),
                        source.getOrganisation(), source.getTitle(), source.getUrl(), source.getAccessedAt())).toList(),
                guide.getEvidence().stream().map(item -> new GuideImportDefinition.Evidence(item.getKey(), item.getStatement(),
                        item.getSupports().stream().map(support -> new GuideImportDefinition.Support(support.getSourceKey(),
                                support.getLocator(), support.getExcerpt(), support.getNote())).toList())).toList());
    }
}
