package info.lifeinuk.backend.support;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.test.util.TestPropertyValues;

/** Test-only PostgreSQL 16 process. Never reads DB_URL or connects to an existing server. */
public final class IsolatedPostgres {
    private static final String URL = start();

    private IsolatedPostgres() { }

    public static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            TestPropertyValues.of(
                    "spring.datasource.url=" + URL,
                    "spring.datasource.username=life_in_uk_test",
                    "spring.datasource.password=",
                    "spring.jpa.hibernate.ddl-auto=validate",
                    "spring.flyway.enabled=true",
                    "life-in-uk.source.bank-holidays.qualified=false",
                    "life-in-uk.source.bank-holidays.qualification-record=",
                    "life-in-uk.source.bank-holidays.use-retention-policy=",
                    "life-in-uk.source.tfl-underground.qualified=false",
                    "life-in-uk.source.tfl-underground.qualification-record=",
                    "life-in-uk.source.tfl-underground.use-retention-policy=")
                    .applyTo(context.getEnvironment());
        }
    }

    private static String start() {
        Path root = null;
        Process server = null;
        try {
            Path bin = Path.of(System.getenv().getOrDefault("TEST_POSTGRES_BIN", "/usr/lib/postgresql/16/bin"));
            root = Files.createTempDirectory("life-in-uk-test-pg-");
            Path data = root.resolve("data");
            Path log = root.resolve("postgres.log");
            run(log, bin.resolve("postgres").toString(), "--version");
            if (!Files.readString(log).contains("PostgreSQL) 16.")) {
                throw new IllegalStateException("Tests require PostgreSQL 16 binaries");
            }
            run(log, bin.resolve("initdb").toString(), "-D", data.toString(),
                    "-A", "trust", "-U", "life_in_uk_test", "--no-locale", "--encoding=UTF8");
            int port;
            try (ServerSocket socket = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
                port = socket.getLocalPort();
            }
            server = new ProcessBuilder(bin.resolve("postgres").toString(), "-D", data.toString(),
                    "-h", "127.0.0.1", "-p", Integer.toString(port), "-k", root.toString(), "-F")
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            String url = "jdbc:postgresql://127.0.0.1:" + port + "/postgres?connectTimeout=1";
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline && server.isAlive()) {
                try (var connection = DriverManager.getConnection(url, "life_in_uk_test", "")) {
                    connection.createStatement().execute("CREATE DATABASE life_in_uk_test");
                    Process startedServer = server;
                    Path startedRoot = root;
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> stop(startedServer, startedRoot)));
                    return "jdbc:postgresql://127.0.0.1:" + port + "/life_in_uk_test";
                } catch (java.sql.SQLException notReady) {
                    Thread.sleep(100);
                }
            }
            throw new IllegalStateException("Isolated PostgreSQL failed to start: " + Files.readString(log));
        } catch (Exception failure) {
            if (server != null) {
                stop(server, root);
            } else if (root != null) {
                delete(root);
            }
            throw new IllegalStateException("Cannot start isolated PostgreSQL 16. Set TEST_POSTGRES_BIN; "
                    + "run as a non-root user. No development-database fallback is permitted.", failure);
        }
    }

    private static void run(Path log, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("PostgreSQL test setup timed out");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("PostgreSQL test setup failed: " + Files.readString(log));
        }
    }

    private static void stop(Process server, Path root) {
        server.destroy();
        try {
            if (!server.waitFor(10, TimeUnit.SECONDS)) {
                server.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (!server.isAlive()) {
                delete(root);
            }
        }
    }

    private static void delete(Path root) {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException failure) {
            System.err.println("Could not remove isolated PostgreSQL test directory: " + root);
        }
    }
}
