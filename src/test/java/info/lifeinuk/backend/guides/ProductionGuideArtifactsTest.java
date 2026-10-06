package info.lifeinuk.backend.guides;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * Corpus-wide invariants for every production Guide artifact, whatever its category. Discovery walks
 * content/guides recursively, so an artifact cannot escape validation by sitting in a subdirectory.
 * Domain-specific editorial checks live in the per-domain artifact tests.
 */
class ProductionGuideArtifactsTest {
    static final Path DIRECTORY = Path.of("content/guides");

    /** Every *.json file under the directory, at any depth, in a stable order. */
    static List<Path> artifacts(Path directory) throws IOException {
        try (var files = Files.walk(directory, FileVisitOption.FOLLOW_LINKS)) {
            return files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
    }

    /** Slugs of all production artifacts, after each has passed the production reader. */
    static Set<String> productionSlugs() throws IOException {
        var slugs = new HashSet<String>();
        for (Path file : artifacts(DIRECTORY)) {
            var input = new GuideImportReader().read(file);
            assertThat(slugs.add(input.slug())).as("unique slug %s", input.slug()).isTrue();
        }
        return slugs;
    }

    @Test
    void everyProductionGuideArtifactHasAUniqueSlugAndValidates() throws IOException {
        var files = artifacts(DIRECTORY);
        assertThat(files).as("production Guide artifacts").isNotEmpty();
        var slugs = new HashSet<String>();
        for (Path file : files) {
            var input = new GuideImportReader().read(file);
            assertThat(slugs.add(input.slug())).as("unique slug %s", input.slug()).isTrue();
            assertThat(file.getFileName().toString()).isEqualTo(input.slug() + "-zh.json");
        }
    }

    @Test
    void discoveryIncludesArtifactsInNestedDirectories(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("top-zh.json"), "{}");
        Files.createDirectories(root.resolve("domain/deeper"));
        Files.writeString(root.resolve("domain/nested-zh.json"), "{}");
        Files.writeString(root.resolve("domain/deeper/deepest-zh.json"), "{}");
        Files.writeString(root.resolve("domain/notes.md"), "not an artifact");

        assertThat(artifacts(root)).containsExactly(root.resolve("domain/deeper/deepest-zh.json"),
                root.resolve("domain/nested-zh.json"), root.resolve("top-zh.json"));
    }
}
