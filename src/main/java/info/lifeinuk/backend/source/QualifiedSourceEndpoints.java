package info.lifeinuk.backend.source;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Read-only module boundary; qualification remains owned by source. */
@Component
public class QualifiedSourceEndpoints {
    private final SourceEndpointRepository endpoints;

    QualifiedSourceEndpoints(SourceEndpointRepository endpoints) {
        this.endpoints = endpoints;
    }

    @Transactional(readOnly = true)
    public SourceEndpoint requireQualified(UUID id) {
        SourceEndpoint endpoint = endpoints.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Source endpoint does not exist"));
        if (!endpoint.isQualifiedAndEnabled()) {
            throw new IllegalStateException("Source endpoint must be qualified and enabled");
        }
        return endpoint;
    }
}
