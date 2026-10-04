package info.lifeinuk.backend.guides;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Database-only public read boundary. Mapping completes before the transaction closes. */
@Service
@Transactional(readOnly = true)
public class GuideQuery {
    private final GuideRepository guides;
    GuideQuery(GuideRepository guides) { this.guides = guides; }
    public List<GuideResponse.Metadata> list() { return List.copyOf(guides.publishedMetadata()); }
    public Optional<GuideResponse.Detail> detail(String slug) {
        return guides.publishedBySlug(slug).map(GuideResponse.Detail::from);
    }
}
