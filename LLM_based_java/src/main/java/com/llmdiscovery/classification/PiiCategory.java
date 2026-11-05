package com.llmdiscovery.classification;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** The 12 PII categories defined by the case study. Declaration order is the response order. */
public enum PiiCategory {
    EMAIL_ADDRESS("email_address"),
    PHONE_NUMBER("phone_number"),
    SSN("ssn"),
    CREDIT_CARD_NUMBER("credit_card_number"),
    NATIONAL_ID_NUMBER("national_id_number"),
    FULL_NAME("full_name"),
    FIRST_NAME("first_name"),
    LAST_NAME("last_name"),
    TCKN("tckn"),
    HOME_ADDRESS("home_address"),
    DATE_OF_BIRTH("date_of_birth"),
    IP_ADDRESS("ip_address");

    private final String value;

    PiiCategory(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    /** The category for an exact value, or null. */
    @JsonCreator
    public static PiiCategory fromValue(String value) {
        for (PiiCategory category : values()) {
            if (category.value.equals(value)) {
                return category;
            }
        }
        return null;
    }
}
