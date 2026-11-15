package info.lifeinuk.backend.guides;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

interface GuideRepository extends JpaRepository<Guide, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Guide> findBySlug(String slug);

    @Query(value = """
            SELECT g.slug, g.category, g.title, g.summary, g.published_at, g.updated_at
            FROM guide g WHERE g.status = 'PUBLISHED'
                AND NOT EXISTS (SELECT 1 FROM guide_locale_current c WHERE c.slug = g.slug
                    AND c.locale = 'zh-CN' AND c.valid_until IS NOT NULL AND c.valid_until <= CURRENT_TIMESTAMP)
            ORDER BY g.published_at DESC, g.slug ASC
            """, nativeQuery = true)
    List<Object[]> publishedMetadataRows();

    @Query(value = """
            SELECT g.* FROM guide g
            WHERE g.slug = :slug AND g.status = 'PUBLISHED'
                AND NOT EXISTS (SELECT 1 FROM guide_locale_current c WHERE c.slug = g.slug
                    AND c.locale = 'zh-CN' AND c.valid_until IS NOT NULL AND c.valid_until <= CURRENT_TIMESTAMP)
            """, nativeQuery = true)
    Optional<Guide> publishedBySlug(@Param("slug") String slug);

    @Query(value = "SELECT g.* FROM guide g WHERE g.slug = :slug AND g.status = 'PUBLISHED'", nativeQuery = true)
    Optional<Guide> publishedGuideForLocale(@Param("slug") String slug);
}
