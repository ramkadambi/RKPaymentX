package com.wellsfargo.payment.canonical.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Canonical, network-agnostic payment lifecycle status.
 * 
 * Maps to/from multiple ISO 20022 message families (pacs/camt/pain) 
 * and network-specific status codes.
 */
public enum PaymentStatus {
    RECEIVED("RECEIVED"),
    PENDING("PENDING"),
    ACCEPTED("ACCEPTED"),
    REJECTED("REJECTED"),
    SETTLED("SETTLED"),
    RETURNED("RETURNED"),
    REVERSED("REVERSED");

    private final String value;

    PaymentStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}

