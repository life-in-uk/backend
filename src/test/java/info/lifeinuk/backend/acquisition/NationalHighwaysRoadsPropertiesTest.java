package info.lifeinuk.backend.acquisition;

import info.lifeinuk.backend.source.Source;
import info.lifeinuk.backend.source.SourceEndpoint;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Binds the real production application.yml against simulated environment variables; entirely offline. */
class NationalHighwaysRoadsPropertiesTest {
    private static final String PREFIX = "life-in-uk.acquisition.national-highways";
    private static final String SENTINEL_KEY = "offline-sentinel-subscription-key-7f3c";

    private static NationalHighwaysRoadsProperties bind(Map<String, Object> environmentVariables) throws Exception {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("simulatedEnvironment", environmentVariables));
        new YamlPropertySourceLoader().load("production", new FileSystemResource("src/main/resources/application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        ConfigurationPropertySources.attach(environment);
        return Binder.get(environment).bindOrCreate(PREFIX, Bindable.of(NationalHighwaysRoadsProperties.class));
    }

    @Test
    void productionConfigurationBindsBothEnvironmentVariablesAndRedactsTheKey() throws Exception {
        var properties = bind(Map.of("NATIONAL_HIGHWAYS_API_KEY", SENTINEL_KEY,
                "NATIONAL_HIGHWAYS_ROADS_BASE_URL", "https://api.data.nationalhighways.co.uk/roads/v2.0"));
        assertThat(properties.apiKey()).isEqualTo(SENTINEL_KEY);
        assertThat(properties.hasApiKey()).isTrue();
        assertThat(properties.roadsBaseUrl()).isEqualTo(SourceEndpoint.NATIONAL_HIGHWAYS_ROADS_BASE_URL);
        assertThat(properties.closuresUrl()).isEqualTo(SourceEndpoint.NATIONAL_HIGHWAYS_ROAD_CLOSURES_URL)
                .doesNotContain(SENTINEL_KEY, "?");
        assertThat(properties.toString()).doesNotContain(SENTINEL_KEY).contains("apiKey=<redacted>");
    }

    @Test
    void absentSecretAndBaseUrlAreSafeAndDefaultToTheCanonicalBase() throws Exception {
        var properties = bind(Map.of());
        assertThat(properties.apiKey()).isEmpty();
        assertThat(properties.hasApiKey()).isFalse();
        assertThat(properties.roadsBaseUrl()).isEqualTo(SourceEndpoint.NATIONAL_HIGHWAYS_ROADS_BASE_URL);
        assertThat(properties.toString()).contains("apiKey=<absent>");
        assertThat(bind(Map.of("NATIONAL_HIGHWAYS_API_KEY", " ")).hasApiKey()).isFalse();
    }

    @Test
    void singleTrailingSlashOnTheCanonicalBaseIsNormalised() throws Exception {
        assertThat(bind(Map.of("NATIONAL_HIGHWAYS_ROADS_BASE_URL", "https://api.data.nationalhighways.co.uk/roads/v2.0/"))
                .closuresUrl()).isEqualTo(SourceEndpoint.NATIONAL_HIGHWAYS_ROAD_CLOSURES_URL);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://example.invalid/roads/v2.0", "http://api.data.nationalhighways.co.uk/roads/v2.0",
        "https://api.data.nationalhighways.co.uk/roads/v1.0", "https://api.data.nationalhighways.co.uk/roads/v2.0/closures",
        "https://api.data.nationalhighways.co.uk/roads/v2.0?subscription-key=x", "https://api.data.nationalhighways.co.uk/roads/v2.0//",
        " https://api.data.nationalhighways.co.uk/roads/v2.0", "https://API.DATA.NATIONALHIGHWAYS.CO.UK/roads/v2.0"
    })
    void noncanonicalBaseUrlFailsWithoutRevealingTheSecret(String baseUrl) {
        var environment = new HashMap<String, Object>();
        environment.put("NATIONAL_HIGHWAYS_ROADS_BASE_URL", baseUrl);
        environment.put("NATIONAL_HIGHWAYS_API_KEY", SENTINEL_KEY);
        assertThatThrownBy(() -> bind(environment)).isInstanceOf(BindException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .satisfies(failure -> {
                    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                        assertThat(String.valueOf(cause.getMessage())).doesNotContain(SENTINEL_KEY);
                    }
                });
    }

    @Test
    void officialRequestContractIsRecordedForLaterAcquisition() {
        assertThat(NationalHighwaysRoadsProperties.SUBSCRIPTION_KEY_HEADER).isEqualTo("Ocp-Apim-Subscription-Key");
        assertThat(NationalHighwaysRoadsProperties.RESPONSE_MEDIA_TYPE_HEADER).isEqualTo("X-Response-MediaType");
        assertThat(NationalHighwaysRoadsProperties.RESPONSE_MEDIA_TYPE).isEqualTo("application/json");
        assertThat(NationalHighwaysRoadsProperties.DATA_FORMAT_HEADER).isEqualTo("X-Data-Format");
        assertThat(NationalHighwaysRoadsProperties.DATA_FORMAT).isEqualTo("DATEXII");
    }

    @Test
    void existingTransportCannotBeUsedForNationalHighwaysBeforeAnyClientInvocation() {
        HttpClient client = mock(HttpClient.class);
        when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        var transport = new JsonEvidenceHttp(client, Duration.ofSeconds(1), "test");
        var endpoint = SourceEndpoint.nationalHighwaysRoadClosures(Source.nationalHighways());
        assertThatIllegalArgumentException().isThrownBy(() -> transport.receive(endpoint));
        verify(client, never()).sendAsync(any(), any());
    }
}
