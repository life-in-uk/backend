package info.lifeinuk.backend.weather;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MetOfficeProperties.class)
class WeatherConfiguration { }
