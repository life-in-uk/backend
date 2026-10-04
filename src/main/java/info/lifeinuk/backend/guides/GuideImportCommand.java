package info.lifeinuk.backend.guides;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Literal CLI option only. Properties/profiles/environment do not invoke an import. */
@Component
class GuideImportCommand implements ApplicationRunner {
    static final String OPTION = "import-guide";
    private static final Logger log = LoggerFactory.getLogger(GuideImportCommand.class);
    private final GuideImportReader reader;
    private final GuideImporter importer;
    GuideImportCommand(GuideImportReader reader, GuideImporter importer) { this.reader = reader; this.importer = importer; }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption(OPTION)) { return; }
        var values = args.getOptionValues(OPTION);
        if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
            throw new IllegalArgumentException("--import-guide requires exactly one local JSON file path");
        }
        // Local read and complete validation finish before the importer opens a database transaction.
        var result = importer.apply(reader.read(Path.of(values.getFirst())));
        log.info("Guide {} import outcome: {}", result.slug(), result.outcome());
    }
}
