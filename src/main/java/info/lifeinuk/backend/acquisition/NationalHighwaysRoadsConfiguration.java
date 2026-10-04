package info.lifeinuk.backend.acquisition;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds external National Highways access configuration only; no client, request or scheduler is defined. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NationalHighwaysRoadsProperties.class)
class NationalHighwaysRoadsConfiguration { }
