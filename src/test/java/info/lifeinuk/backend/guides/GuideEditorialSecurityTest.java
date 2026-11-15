package info.lifeinuk.backend.guides;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuideEditorialSecurityTest {
    @Test
    void codexLocalReviewCredentialRequiresLocalSpringProfile() {
        var productionLike = new MockEnvironment();
        assertThatThrownBy(() -> new GuideEditorialSecurity("draft", "review", "mock", "approval", "local", productionLike))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only allowed with the local Spring profile");

        var local = new MockEnvironment();
        local.setActiveProfiles("local");
        assertThatCode(() -> new GuideEditorialSecurity("draft", "review", "mock", "approval", "local", local))
                .doesNotThrowAnyException();
    }
}
