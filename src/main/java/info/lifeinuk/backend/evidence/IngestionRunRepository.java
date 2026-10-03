package info.lifeinuk.backend.evidence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

interface IngestionRunRepository extends Repository<IngestionRun, UUID> {
    IngestionRun save(IngestionRun run);
    Optional<IngestionRun> findById(UUID id);
}
