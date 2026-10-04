package info.lifeinuk.backend.evidence;

import info.lifeinuk.backend.source.Source;
import info.lifeinuk.backend.source.SourceEndpoint;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NationalHighwaysRunEvidenceTest {
    @ParameterizedTest
    @ValueSource(strings={"source","endpoint","scope","url","status","qualification"})
    @SuppressWarnings("unchecked")
    void rejectsEveryWrongIdentityOrEligibilityBeforeLoadingPages(String fault) {
        var em=mock(EntityManager.class);var query=mock(TypedQuery.class);
        var run=mock(IngestionRun.class);var endpoint=mock(SourceEndpoint.class);var source=mock(Source.class);
        UUID id=UUID.randomUUID();
        when(em.createQuery(anyString(),eq(IngestionRun.class))).thenReturn(query);
        when(query.setParameter("id",id)).thenReturn(query);when(query.getResultStream()).thenReturn(Stream.of(run));
        when(run.getSourceEndpoint()).thenReturn(endpoint);when(endpoint.getSource()).thenReturn(source);
        when(run.getStatus()).thenReturn(fault.equals("status")?RunStatus.FAILED:RunStatus.SUCCESS);
        when(endpoint.isQualifiedAndEnabled()).thenReturn(!fault.equals("qualification"));
        when(source.getKey()).thenReturn(fault.equals("source")?Source.TFL_KEY:Source.NATIONAL_HIGHWAYS_KEY);
        when(source.getScope()).thenReturn(fault.equals("scope")?Source.TFL_UNDERGROUND_SCOPE:Source.NATIONAL_HIGHWAYS_ROAD_CLOSURES_SCOPE);
        when(endpoint.getKey()).thenReturn(fault.equals("endpoint")?SourceEndpoint.TFL_UNDERGROUND_KEY:SourceEndpoint.NATIONAL_HIGHWAYS_ROAD_CLOSURES_KEY);
        when(endpoint.getUrl()).thenReturn(fault.equals("url")?SourceEndpoint.TFL_UNDERGROUND_URL:SourceEndpoint.NATIONAL_HIGHWAYS_ROAD_CLOSURES_URL);
        assertThatIllegalArgumentException().isThrownBy(()->new NationalHighwaysRunEvidence(em).requireSuccessful(id));
        verify(em,never()).createQuery(anyString(),eq(EvidenceArtifact.class));
    }
}
