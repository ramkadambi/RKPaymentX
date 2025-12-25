package com.wellsfargo.payment.canonical.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Canonical satellite/service execution status.
 */
public enum ServiceResultStatus {
    PASS("PASS"),
    FAIL("FAIL"),
    ERROR("ERROR");

    private final String value;

    ServiceResultStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}

