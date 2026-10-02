package info.lifeinuk.backend.source;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("life-in-uk.source.bank-holidays")
public record BankHolidaysBootstrapProperties(
        @DefaultValue("false") boolean qualified,
        @DefaultValue("") String qualificationRecord,
        @DefaultValue("") String useRetentionPolicy) {
    public BankHolidaysBootstrapProperties {
        if (qualified && (qualificationRecord == null || qualificationRecord.isBlank()
                || useRetentionPolicy == null || useRetentionPolicy.isBlank())) {
            throw new IllegalArgumentException("Qualified bootstrap requires an explicit record and use/retention policy");
        }
    }
}
