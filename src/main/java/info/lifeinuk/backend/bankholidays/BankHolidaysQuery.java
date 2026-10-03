package info.lifeinuk.backend.bankholidays;

import info.lifeinuk.backend.evidence.BankHolidaysEvidence;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Selection commits its short read-only transaction before interpretation begins. */
@Service
public class BankHolidaysQuery {
    private final BankHolidaysEvidence evidence;
    private final GovUkBankHolidaysParser parser;

    BankHolidaysQuery(BankHolidaysEvidence evidence, GovUkBankHolidaysParser parser) {
        this.evidence = evidence;
        this.parser = parser;
    }

    public Optional<BankHolidaysResponse> read() {
        return evidence.latestSuccessful().map(artifact -> {
            BankHolidaysInterpretation interpretation;
            try {
                interpretation = parser.parse(artifact);
            } catch (IllegalArgumentException invalid) {
                // No source values, raw payload or parser diagnostics cross the public boundary.
                throw new InvalidEvidence();
            }
            return BankHolidaysResponse.from(interpretation, artifact.getObservedAt());
        });
    }

    static final class InvalidEvidence extends RuntimeException { }
}
