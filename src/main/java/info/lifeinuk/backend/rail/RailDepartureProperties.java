package info.lifeinuk.backend.rail;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Separate subscription; no guessed gateway URL or reuse of arrival credentials. */
@ConfigurationProperties("life-in-uk.rail-departures")
public record RailDepartureProperties(@DefaultValue("") String baseUrl, @DefaultValue("") String apiKey) {
    boolean configured() { return baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank(); }
    @Override public String toString() { return "RailDepartureProperties[apiKey=<redacted>]"; }
}
