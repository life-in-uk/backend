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
    public SourceEndpoint requireBankHolidays() {
        SourceEndpoint endpoint = endpoints.findByKey(SourceEndpoint.BANK_HOLIDAYS_KEY)
                .orElseThrow(() -> new IllegalArgumentException("Bank Holidays endpoint does not exist"));
        requireEligible(endpoint);
        if (!Source.BANK_HOLIDAYS_KEY.equals(endpoint.getSource().getKey())
                || !SourceEndpoint.BANK_HOLIDAYS_URL.equals(endpoint.getUrl())) {
            throw new IllegalStateException("Bank Holidays endpoint must retain its approved identity");
        }
        return endpoint;
    }

    @Transactional(readOnly = true)
    public SourceEndpoint requireTflUnderground() {
        SourceEndpoint endpoint = endpoints.findByKey(SourceEndpoint.TFL_UNDERGROUND_KEY)
                .orElseThrow(() -> new IllegalArgumentException("TfL Underground endpoint does not exist"));
        requireEligible(endpoint);
        if (!endpoint.hasCanonicalTflIdentity()) {
            throw new IllegalStateException("TfL Underground endpoint must retain its approved identity");
        }
        return endpoint;
    }

    @Transactional(readOnly = true)
    public SourceEndpoint requireQualified(UUID id) {
        SourceEndpoint endpoint = endpoints.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Source endpoint does not exist"));
        requireEligible(endpoint);
        return endpoint;
    }

    private void requireEligible(SourceEndpoint endpoint) {
        if (!endpoint.isQualifiedAndEnabled()) {
            throw new IllegalStateException("Source endpoint must be qualified and enabled");
        }
    }
}
