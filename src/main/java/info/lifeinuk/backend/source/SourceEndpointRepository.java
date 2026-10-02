package info.lifeinuk.backend.source;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SourceEndpointRepository extends JpaRepository<SourceEndpoint, UUID> {
    Optional<SourceEndpoint> findByKey(String key);
}
