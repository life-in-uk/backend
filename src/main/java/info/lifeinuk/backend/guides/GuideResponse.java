package info.lifeinuk.backend.guides;

import java.time.Instant;
import java.util.List;

/** Public DTOs only; list metadata has no body or persistence identity. */
public final class GuideResponse {
    private GuideResponse() { }
    public record Metadata(String slug, String category, String title, String summary,
            Instant publishedAt, Instant updatedAt) { }
    public record Detail(String slug, String category, String title, String summary, String content,
            Instant publishedAt, Instant updatedAt, List<Source> sources) {
        public Detail { sources = List.copyOf(sources); }
        static Detail from(Guide guide) {
            return new Detail(guide.getSlug(), guide.getCategory(), guide.getTitle(), guide.getSummary(), guide.getContent(),
                    guide.getPublishedAt(), guide.getUpdatedAt(), guide.getSources().stream().map(source ->
                        new Source(source.getOrganisation(), source.getTitle(), source.getUrl(), source.getAccessedAt())).toList());
        }
    }
    public record Source(String organisation, String title, String url, Instant accessedAt) { }
}
