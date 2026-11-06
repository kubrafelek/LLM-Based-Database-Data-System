package com.llmdiscovery.common;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Turns an invalid .env into a short "APPLICATION FAILED TO START" report instead of a stack trace. */
public class InvalidConfigurationAnalyzer extends AbstractFailureAnalyzer<InvalidConfigurationException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, InvalidConfigurationException cause) {
        return new FailureAnalysis(
                "Invalid configuration in .env:\n  - " + String.join("\n  - ", cause.problems()),
                "Fix the values in .env (see .env.example for how to generate them) and restart.",
                cause);
    }
}
