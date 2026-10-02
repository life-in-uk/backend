package info.lifeinuk.backend.source;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SourceRepository extends JpaRepository<Source, UUID> {
    Optional<Source> findByKey(String key);
}
