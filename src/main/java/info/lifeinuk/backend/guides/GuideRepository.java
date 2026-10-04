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

    @Query("""
            select new info.lifeinuk.backend.guides.GuideResponse$Metadata(
                g.slug, g.category, g.title, g.summary, g.publishedAt, g.updatedAt)
            from Guide g where g.status = info.lifeinuk.backend.guides.GuideStatus.PUBLISHED
            order by g.publishedAt desc, g.slug asc
            """)
    List<GuideResponse.Metadata> publishedMetadata();

    @Query("""
            select g from Guide g left join fetch g.sources
            where g.slug = :slug and g.status = info.lifeinuk.backend.guides.GuideStatus.PUBLISHED
            """)
    Optional<Guide> publishedBySlug(@Param("slug") String slug);
}
