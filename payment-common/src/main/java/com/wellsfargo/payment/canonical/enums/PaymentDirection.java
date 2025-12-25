package com.wellsfargo.payment.canonical.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Payment direction relative to Wells Fargo.
 */
public enum PaymentDirection {
    INBOUND("INBOUND"),    // Payment coming into Wells Fargo
    OUTBOUND("OUTBOUND"),  // Payment going out from Wells Fargo
    INTERNAL("INTERNAL");  // Internal transfer within Wells Fargo

    private final String value;

    PaymentDirection(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}

