package com.wellsfargo.payment.satellites.paymentposting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Idempotency Service for checking if transactions have already been processed.
 * 
 * In production, this would query Redis using end_to_end_id as key.
 * For now, uses in-memory cache for testing.
 * 
 * Exactly-once semantics: Transaction with same end_to_end_id can only be posted once.
 */
@Service
public class IdempotencyService {
    
    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    
    // In-memory cache for idempotency checking (in production, this would be Redis)
    // Key: end_to_end_id, Value: transaction status
    private final Set<String> processedTransactions = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    /**
     * Check if a transaction has already been processed (idempotency check).
     * 
     * In production, this would query Redis:
     * <pre>
     * Boolean exists = redisClient.exists("transaction:" + endToEndId);
     * return exists;
     * </pre>
     * 
     * @param endToEndId End-to-end transaction identifier
     * @return True if transaction already processed, False otherwise
     */
    public boolean isTransactionProcessed(String endToEndId) {
        if (endToEndId == null || endToEndId.trim().isEmpty()) {
            return false;
        }
        
        boolean exists = processedTransactions.contains(endToEndId.trim());
        
        if (exists) {
            log.warn("Transaction already processed (idempotency check): E2E={}", endToEndId);
        }
        
        return exists;
    }
    
    /**
     * Mark a transaction as processed.
     * 
     * In production, this would update Redis:
     * <pre>
     * redisClient.setex(
     *     "transaction:" + endToEndId,
     *     86400, // 24 hours TTL
     *     "POSTED"
     * );
     * </pre>
     * 
     * @param endToEndId End-to-end transaction identifier
     */
    public void markTransactionProcessed(String endToEndId) {
        if (endToEndId == null || endToEndId.trim().isEmpty()) {
            return;
        }
        
        processedTransactions.add(endToEndId.trim());
        log.debug("Marked transaction as processed: E2E={}", endToEndId);
    }
    
    /**
     * Clear all processed transactions (for testing only).
     */
    public void clear() {
        processedTransactions.clear();
        log.debug("Cleared all processed transactions");
    }
}

