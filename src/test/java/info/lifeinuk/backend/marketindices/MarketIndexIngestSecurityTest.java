package info.lifeinuk.backend.marketindices;

import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MarketIndexIngestSecurityTest {
    static final String SECRET_A = "a".repeat(20) + "-collector-secret-one-xyz";
    static final String SECRET_B = "b".repeat(20) + "-collector-secret-two-xyz";

    static String hash(String secret) { return HexFormat.of().formatHex(MarketIndexIngestSecurity.sha256(secret)); }

    static MarketIndexIngestSecurity security(String keys, MockEnvironment environment) {
        var properties = new MarketIndexProperties(keys, List.of(), false, List.of(), "", Duration.ofDays(30),
                Duration.ofDays(730), Duration.ofMinutes(20), Duration.ofMinutes(60), Duration.ofMinutes(30),
                Duration.ofMinutes(6), Duration.ofMinutes(2), Duration.ofMinutes(5));
        return new MarketIndexIngestSecurity(properties, environment);
    }

    @Test
    void noKeysMeansDisabled() {
        var security = security("", new MockEnvironment());
        assertThat(security.configured()).isFalse();
        assertThat(security.authenticate("k1." + SECRET_A)).isNull();
    }

    @Test
    void authenticatesOnlyTheMatchingKeyIdAndSecret() {
        var security = security("k2026a:" + hash(SECRET_A) + ",k2026b:" + hash(SECRET_B), new MockEnvironment());
        assertThat(security.authenticate("k2026a." + SECRET_A)).isEqualTo("k2026a");
        assertThat(security.authenticate("k2026b." + SECRET_B)).isEqualTo("k2026b");
        assertThat(security.authenticate("k2026b." + SECRET_A)).isNull();
        assertThat(security.authenticate("k2026a." + SECRET_A + "x")).isNull();
        assertThat(security.authenticate(SECRET_A)).isNull();
        assertThat(security.authenticate("k2026a.short")).isNull();
        assertThat(security.authenticate(null)).isNull();
        assertThat(security.authenticate("k2026a." + "x".repeat(400))).isNull();
    }

    @Test
    void keyConfigurationIsStrict() {
        var env = new MockEnvironment();
        assertThatIllegalArgumentException().isThrownBy(() -> security("k1:" + SECRET_A, env));
        assertThatIllegalArgumentException().isThrownBy(() -> security("K1:" + hash(SECRET_A), env));
        assertThatIllegalArgumentException().isThrownBy(() -> security("k1:" + hash(SECRET_A) + ",k1:" + hash(SECRET_B), env));
        assertThatIllegalArgumentException().isThrownBy(() -> security("k1:" + hash(SECRET_A) + ",k2:" + hash(SECRET_A), env));
        assertThatIllegalArgumentException().isThrownBy(() -> security(
                "k1:" + hash(SECRET_A) + ",k2:" + hash(SECRET_B) + ",k3:" + hash("c".repeat(40)), env));
    }

    @Test
    void collectorCredentialMustDifferFromEditorialCredentials() {
        var env = new MockEnvironment().withProperty("life-in-uk.editorial.review-token", SECRET_A);
        assertThatIllegalArgumentException().isThrownBy(() -> security("k1:" + hash(SECRET_A), env));
        assertThat(security("k1:" + hash(SECRET_B), env).configured()).isTrue();
    }
}
