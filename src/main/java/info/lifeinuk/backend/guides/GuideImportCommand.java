package info.lifeinuk.backend.guides;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Retains the old switch only to fail closed; public projection imports are no longer supported. */
@Component
class GuideImportCommand implements ApplicationRunner {
    static final String OPTION = "import-guide";

    @Override
    public void run(ApplicationArguments args) {
        if (args.containsOption(OPTION)) {
            throw new IllegalArgumentException("--import-guide is disabled because it writes directly to public Guides; "
                    + "use the reviewed revision and publication workflow");
        }
    }
}
