package com.llmdiscovery.common;

import java.util.List;

/** Thrown at startup when .env values are missing or unusable; reported by {@link InvalidConfigurationAnalyzer}. */
public class InvalidConfigurationException extends RuntimeException {

    private final List<String> problems;

    public InvalidConfigurationException(List<String> problems) {
        super("Invalid configuration in .env: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
