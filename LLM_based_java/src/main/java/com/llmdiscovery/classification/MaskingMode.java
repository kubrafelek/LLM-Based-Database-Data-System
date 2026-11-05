package com.llmdiscovery.classification;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** How sample values are masked before they are sent to the LLM. */
public enum MaskingMode {
    NONE,
    PARTIAL,
    STRICT;

    @JsonValue
    public String value() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static MaskingMode fromValue(String value) {
        return valueOf(value.strip().toUpperCase());
    }
}
