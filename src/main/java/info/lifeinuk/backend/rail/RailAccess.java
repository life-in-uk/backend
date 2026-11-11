package info.lifeinuk.backend.rail;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Backend Rail feature flag, OFF by default. While off, no Rail endpoint contacts RDM, even if Rail
 * credentials are present in the environment. Enabling it is an owner decision that requires the RDM
 * Schedule 2 policy review, rate-limit and licensing sign-off.
 */
@ConfigurationProperties("life-in-uk.rail-access")
public record RailAccess(@DefaultValue("false") boolean enabled) { }
