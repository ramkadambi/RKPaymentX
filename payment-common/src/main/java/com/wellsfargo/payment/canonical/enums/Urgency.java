package com.wellsfargo.payment.canonical.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Payment urgency level.
 */
public enum Urgency {
    NORMAL("NORMAL"),
    HIGH("HIGH"),
    URGENT("URGENT");

    private final String value;

    Urgency(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}

