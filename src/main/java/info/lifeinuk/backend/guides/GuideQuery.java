package info.lifeinuk.backend.guides;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Database-only public read boundary. Mapping completes before the transaction closes. */
@Service
@Transactional(readOnly = true)
public class GuideQuery {
    private static final Set<String> LOCALES = Set.of("zh-CN", "zh-TW", "en", "ur", "hi");
    private final GuideRepository guides;
    private final GuideContentBatchStore batches;
    private final GuideRevisionService revisions;
    GuideQuery(GuideRepository guides, GuideContentBatchStore batches, GuideRevisionService revisions) {
        this.guides = guides; this.batches = batches; this.revisions = revisions;
    }
    public List<GuideResponse.Metadata> list() {
        return guides.publishedMetadataRows().stream().map(row -> new GuideResponse.Metadata(
                (String) row[0], (String) row[1], (String) row[2], (String) row[3],
                instant(row[4]), instant(row[5]))).toList();
    }
    public Optional<List<GuideResponse.Metadata>> localizedList(String locale) {
        if (!LOCALES.contains(locale)) return Optional.empty();
        if ("zh-CN".equals(locale)) return Optional.of(list());
        return Optional.of(batches.publishedLocaleMetadata(locale).stream().map(row -> {
            var definition = revisions.definition(row.canonicalForm());
            return new GuideResponse.Metadata(row.slug(), definition.category(), definition.title(), definition.summary(),
                    row.publishedAt(), row.updatedAt());
        }).toList());
    }
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Optional<GuideResponse.Detail> detail(String slug) {
        return guides.publishedBySlug(slug).map(GuideResponse.Detail::from);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Optional<LocalizedGuideResponse> localizedDetail(String slug, String locale) {
        if (!Set.of("zh-CN", "zh-TW", "en", "ur", "hi").contains(locale)) { return Optional.empty(); }
        var current = batches.current(slug, locale);
        if (current.isEmpty()) { return locale.equals("zh-CN") ? detail(slug).map(value -> new LocalizedGuideResponse(locale, value,
                null, null)) : Optional.empty(); }
        var publicGuide = guides.publishedGuideForLocale(slug).orElse(null);
        if (publicGuide == null) { return Optional.empty(); }
        if ("zh-CN".equals(locale)) {
            return Optional.of(new LocalizedGuideResponse(locale, GuideResponse.Detail.from(publicGuide),
                    current.get().sourceRevisionId(), current.get().contentHash()));
        }
        var definition = revisions.definition(current.get().canonicalForm());
        var detail = new GuideResponse.Detail(slug, definition.category(), definition.title(), definition.summary(),
                definition.content(), publicGuide.getPublishedAt(), publicGuide.getUpdatedAt(),
                definition.sources().stream().map(source -> new GuideResponse.Source(source.key(), source.organisation(),
                        source.title(), source.url(), source.accessedAt())).toList(),
                definition.evidence().stream().map(item -> new GuideResponse.Evidence(item.key(), item.statement(),
                        item.supports().stream().map(support -> new GuideResponse.Support(support.sourceKey(), support.locator(),
                                support.excerpt(), support.note())).toList())).toList());
        return Optional.of(new LocalizedGuideResponse(locale, detail, current.get().sourceRevisionId(), current.get().contentHash()));
    }

    public record LocalizedGuideResponse(String locale, GuideResponse.Detail guide, UUID sourceRevisionId, String contentHash) { }

    private static java.time.Instant instant(Object value) {
        if (value instanceof java.time.OffsetDateTime timestamp) return timestamp.toInstant();
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.time.Instant timestamp) return timestamp;
        throw new IllegalStateException("Unexpected Guide timestamp type: " + (value == null ? "null" : value.getClass().getName()));
    }
}
