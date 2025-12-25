package com.wellsfargo.payment.canonical.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Selected payment routing network.
 */
public enum RoutingNetwork {
    INTERNAL("INTERNAL"),  // IBT - Internal Bank Transfer
    FED("FED"),            // Federal Reserve Wire Network
    CHIPS("CHIPS"),        // Clearing House Interbank Payments System
    SWIFT("SWIFT");        // SWIFT Network

    private final String value;

    RoutingNetwork(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}

