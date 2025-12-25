package com.wellsfargo.payment.satellites.paymentposting;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Internal representation of a posting ledger entry.
 * 
 * This represents a single debit or credit entry that will be posted to the ledger.
 * In production, these entries will be persisted to MongoDB.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostingEntry {
    /**
     * End-to-end transaction identifier.
     */
    private String endToEndId;
    
    /**
     * Side of the entry (DEBIT or CREDIT).
     */
    private EntrySide side;
    
    /**
     * Agent identifier (e.g., BIC or other identifier, network agnostic).
     */
    private String agentId;
    
    /**
     * Amount of the entry.
     */
    private BigDecimal amount;
    
    /**
     * Currency code (e.g., USD).
     */
    private String currency;
    
    /**
     * Wells Fargo internal settlement account.
     */
    private String settlementAccount;
    
    /**
     * Account type (VOSTRO, NOSTRO, FED, CHIPS_NOSTRO, SWIFT_NOSTRO, CUSTOMER, etc.).
     */
    private String accountType;
    
    /**
     * ISO 8601 timestamp.
     */
    private String timestamp;
}

