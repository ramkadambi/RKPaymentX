package com.wellsfargo.payment.canonical.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * PACS.002 status codes for payment status reporting.
 * 
 * These status codes are sent to the instructing bank via SWIFT pacs.002 messages
 * to report the current status of payment processing.
 */
public enum Pacs002Status {
    /**
     * RCVD - Payment received.
     * Sent when payment is first received from the instructing bank.
     */
    RCVD("RCVD"),
    
    /**
     * ACCP - Accepted for processing (basic validation passed).
     * Sent after basic validation checks pass (account validation).
     */
    ACCP("ACCP"),
    
    /**
     * ACCC - Accepted after customer profile validation (e.g., KYC/AML checks).
     * Sent after customer profile validation passes.
     */
    ACCC("ACCC"),
    
    /**
     * PDNG - Pending further checks (e.g., sanctions, balance).
     * Sent when payment is pending additional checks like sanctions or balance verification.
     */
    PDNG("PDNG"),
    
    /**
     * ACSP - Accepted, settlement in process.
     * Sent when payment is accepted and settlement is in progress.
     */
    ACSP("ACSP"),
    
    /**
     * ACSC - Accepted, settlement completed (funds credited).
     * Sent when payment settlement is complete and funds have been credited.
     */
    ACSC("ACSC"),
    
    /**
     * RJCT - Rejected (invalid account, sanctions fail, etc.).
     * Sent when payment is rejected at any stage.
     */
    RJCT("RJCT"),
    
    /**
     * CANC - Cancelled (after camt.056 request).
     * Sent when payment is cancelled after receiving a camt.056 cancellation request.
     */
    CANC("CANC");

    private final String value;

    Pacs002Status(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
    
    /**
     * Get status from string value.
     */
    public static Pacs002Status fromValue(String value) {
        for (Pacs002Status status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown Pacs002Status: " + value);
    }
}

