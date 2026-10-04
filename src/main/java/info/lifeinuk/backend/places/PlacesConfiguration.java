package info.lifeinuk.backend.places;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OsNamesProperties.class)
class PlacesConfiguration { }
