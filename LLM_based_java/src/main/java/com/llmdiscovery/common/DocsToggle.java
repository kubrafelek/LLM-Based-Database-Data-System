package com.llmdiscovery.common;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** APP_ENV=production disables the Swagger UI and the OpenAPI schema (runs after .env is loaded). */
public class DocsToggle implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean enabled = !"production".equals(environment.getProperty("APP_ENV", "development"));
        environment.getPropertySources().addFirst(new MapPropertySource("docs-toggle", Map.of(
                "springdoc.api-docs.enabled", enabled,
                "springdoc.swagger-ui.enabled", enabled)));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
