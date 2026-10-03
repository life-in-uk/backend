package info.lifeinuk.backend.source;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("life-in-uk.source.tfl-underground")
public record TflUndergroundBootstrapProperties(
        @DefaultValue("false") boolean qualified,
        @DefaultValue("") String qualificationRecord,
        @DefaultValue("") String useRetentionPolicy) {
    public TflUndergroundBootstrapProperties {
        if (qualified && (qualificationRecord == null || qualificationRecord.isBlank()
                || useRetentionPolicy == null || useRetentionPolicy.isBlank())) {
            throw new IllegalArgumentException("Qualified bootstrap requires an explicit record and use/retention policy");
        }
    }
}
