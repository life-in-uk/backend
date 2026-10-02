package info.lifeinuk.backend;

import info.lifeinuk.backend.support.IsolatedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

@SpringBootTest
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
class LifeInUkBackendApplicationTests {

    @Test
    void contextLoads() {
    }
}
