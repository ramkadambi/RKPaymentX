package com.wellsfargo.payment.canonical.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Creditor account type for clearing/routing decisions.
 */
public enum CreditorType {
    BANK("BANK"),
    INDIVIDUAL("INDIVIDUAL"),
    CORPORATE("CORPORATE");

    private final String value;

    CreditorType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}

