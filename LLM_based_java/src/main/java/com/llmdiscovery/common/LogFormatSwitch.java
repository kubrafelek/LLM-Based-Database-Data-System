package com.llmdiscovery.common;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * LOG_FORMAT=json switches console logging to {@link JsonLogFormatter}. Runs after .env is loaded and
 * before the logging system starts, so even the startup lines are JSON.
 */
public class LogFormatSwitch implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if ("json".equals(environment.getProperty("LOG_FORMAT", "text"))) {
            environment.getPropertySources().addFirst(new MapPropertySource("log-format", Map.of(
                    "logging.structured.format.console", JsonLogFormatter.class.getName())));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
