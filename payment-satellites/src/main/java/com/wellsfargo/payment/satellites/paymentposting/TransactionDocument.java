package com.wellsfargo.payment.satellites.paymentposting;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

/**
 * MongoDB document structure for a payment transaction.
 * 
 * This represents the complete transaction that will be persisted to MongoDB.
 * Contains both debit and credit entries for double-entry bookkeeping.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionDocument {
    /**
     * End-to-end transaction identifier (primary key for idempotency).
     */
    private String endToEndId;
    
    /**
     * Transaction identifier (optional).
     */
    private String transactionId;
    
    /**
     * Message identifier.
     */
    private String msgId;
    
    /**
     * Transaction amount.
     */
    private BigDecimal amount;
    
    /**
     * Currency code (e.g., USD).
     */
    private String currency;
    
    /**
     * Debit entry as a map (for MongoDB serialization).
     */
    private Map<String, Object> debitEntry;
    
    /**
     * Credit entry as a map (for MongoDB serialization).
     */
    private Map<String, Object> creditEntry;
    
    /**
     * Routing network (INTERNAL, FED, CHIPS, SWIFT).
     */
    private String routingNetwork;
    
    /**
     * Transaction status (POSTED).
     */
    @Builder.Default
    private String status = "POSTED";
    
    /**
     * ISO 8601 timestamp when transaction was posted.
     */
    private String postedTimestamp;
    
    /**
     * ISO 8601 timestamp when transaction was created.
     */
    private String createdTimestamp;
}

