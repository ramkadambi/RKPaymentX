package com.wellsfargo.payment.canonical.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * ISO 20022 External Return Reason Codes for PACS.004 (Payment Return).
 * 
 * These codes are used in PACS.004 messages to indicate why a payment
 * is being returned to the instructing bank.
 * 
 * Reference: ISO 20022 External Code Sets
 */
public enum ReturnReasonCode {
    /**
     * AC01 - Account identifier incorrect.
     * Invalid or closed beneficiary account.
     */
    AC01("AC01", "Account identifier incorrect"),
    
    /**
     * AC04 - Account closed.
     * Beneficiary account no longer active.
     */
    AC04("AC04", "Account closed"),
    
    /**
     * AC06 - Account blocked.
     * Account frozen due to legal/regulatory reasons.
     */
    AC06("AC06", "Account blocked"),
    
    /**
     * AM04 - Insufficient funds.
     * Debtor account lacks sufficient balance.
     */
    AM04("AM04", "Insufficient funds"),
    
    /**
     * AM05 - Duplicate payment.
     * Payment already processed.
     */
    AM05("AM05", "Duplicate payment"),
    
    /**
     * BE04 - Incorrect creditor name.
     * Beneficiary name mismatch.
     */
    BE04("BE04", "Incorrect creditor name"),
    
    /**
     * BE05 - Creditor missing.
     * Beneficiary details not provided.
     */
    BE05("BE05", "Creditor missing"),
    
    /**
     * NARR - Narrative reason.
     * Free-text explanation when no standard code fits.
     */
    NARR("NARR", "Narrative reason"),
    
    /**
     * RC01 - Invalid bank identifier.
     * Wrong BIC/clearing code for creditor agent.
     */
    RC01("RC01", "Invalid bank identifier"),
    
    /**
     * RR01 - Regulatory reason.
     * Blocked due to sanctions/AML rules.
     */
    RR01("RR01", "Regulatory reason"),
    
    /**
     * RR02 - Regulatory reporting incomplete.
     * Missing mandatory compliance data.
     */
    RR02("RR02", "Regulatory reporting incomplete"),
    
    /**
     * AG01 - Transaction forbidden.
     * Payment type not allowed (e.g., blocked service).
     */
    AG01("AG01", "Transaction forbidden"),
    
    /**
     * AG02 - Invalid bank operation.
     * Instruction not permitted by receiving bank.
     */
    AG02("AG02", "Invalid bank operation");

    private final String code;
    private final String description;

    ReturnReasonCode(String code, String description) {
        this.code = code;
        this.description = description;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
    
    /**
     * Get return reason code from string value.
     */
    public static ReturnReasonCode fromCode(String code) {
        for (ReturnReasonCode reasonCode : values()) {
            if (reasonCode.code.equals(code)) {
                return reasonCode;
            }
        }
        throw new IllegalArgumentException("Unknown ReturnReasonCode: " + code);
    }
}

