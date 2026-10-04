package info.lifeinuk.backend.guides;

import java.time.Instant;
import java.util.List;

/** Public DTOs only; list metadata has no body or persistence identity. */
public final class GuideResponse {
    private GuideResponse() { }
    public record Metadata(String slug, String category, String title, String summary,
            Instant publishedAt, Instant updatedAt) { }
    public record Detail(String slug, String category, String title, String summary, String content,
            Instant publishedAt, Instant updatedAt, List<Source> sources, List<Evidence> evidence) {
        public Detail { sources = List.copyOf(sources); evidence = List.copyOf(evidence); }
        static Detail from(Guide guide) {
            return new Detail(guide.getSlug(), guide.getCategory(), guide.getTitle(), guide.getSummary(), guide.getContent(),
                    guide.getPublishedAt(), guide.getUpdatedAt(), guide.getSources().stream().map(source ->
                        new Source(source.getKey(), source.getOrganisation(), source.getTitle(), source.getUrl(), source.getAccessedAt())).toList(),
                    guide.getEvidence().stream().map(item -> new Evidence(item.getKey(), item.getStatement(),
                        item.getSupports().stream().map(support -> new Support(support.getSourceKey(), support.getLocator(),
                                support.getExcerpt(), support.getNote())).toList())).toList());
        }
    }
    public record Source(String key, String organisation, String title, String url, Instant accessedAt) { }
    public record Evidence(String key, String statement, List<Support> supports) {
        public Evidence { supports = List.copyOf(supports); }
    }
    public record Support(String sourceKey, String locator, String excerpt, String note) { }
}
