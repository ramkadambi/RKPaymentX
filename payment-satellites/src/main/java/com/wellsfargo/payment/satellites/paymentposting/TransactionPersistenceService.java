package com.wellsfargo.payment.satellites.paymentposting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Transaction Persistence Service for persisting transactions to MongoDB.
 * 
 * In production, this would:
 * 1. Use MongoDB client to insert document
 * 2. Use end_to_end_id as unique index for idempotency
 * 3. Handle duplicate key errors (idempotency)
 * 4. Update Redis cache for fast lookups
 * 
 * For now, uses in-memory storage for testing.
 */
@Service
public class TransactionPersistenceService {
    
    private static final Logger log = LoggerFactory.getLogger(TransactionPersistenceService.class);
    
    @Autowired
    private IdempotencyService idempotencyService;
    
    // In-memory storage for transactions (in production, this would be MongoDB)
    // Key: end_to_end_id, Value: TransactionDocument
    private final Map<String, TransactionDocument> transactionStore = new ConcurrentHashMap<>();
    
    /**
     * Persist transaction document to MongoDB.
     * 
     * In production, this would:
     * <pre>
     * try {
     *     // Use end_to_end_id as unique index for idempotency
     *     mongoCollection.insertOne(transactionDoc);
     *     
     *     // Update Redis cache for fast idempotency checks
     *     redisClient.setex(
     *         "transaction:" + transactionDoc.getEndToEndId(),
     *         86400, // 24 hours TTL
     *         "POSTED"
     *     );
     *     
     *     return true;
     * } catch (DuplicateKeyException e) {
     *     // Transaction already exists - idempotency
     *     return true; // Success (already processed)
     * } catch (Exception e) {
     *     log.error("Failed to persist transaction", e);
     *     return false;
     * }
     * </pre>
     * 
     * @param transactionDoc TransactionDocument to persist
     * @return True if successful, False otherwise
     */
    public boolean persistTransaction(TransactionDocument transactionDoc) {
        if (transactionDoc == null || transactionDoc.getEndToEndId() == null) {
            log.error("Cannot persist null transaction or transaction without end_to_end_id");
            return false;
        }
        
        String endToEndId = transactionDoc.getEndToEndId();
        
        try {
            // Check if already exists (idempotency)
            if (transactionStore.containsKey(endToEndId)) {
                log.warn("Transaction already exists in store (idempotency): E2E={}", endToEndId);
                return true; // Success (already processed)
            }
            
            // Store transaction
            transactionStore.put(endToEndId, transactionDoc);
            
            // Mark as processed in idempotency service
            idempotencyService.markTransactionProcessed(endToEndId);
            
            log.info("Persisted transaction to store: E2E={}, Debit={}, Credit={}", 
                endToEndId,
                transactionDoc.getDebitEntry() != null 
                    ? transactionDoc.getDebitEntry().get("settlement_account") : "N/A",
                transactionDoc.getCreditEntry() != null 
                    ? transactionDoc.getCreditEntry().get("settlement_account") : "N/A");
            
            return true;
        } catch (Exception e) {
            log.error("Failed to persist transaction: E2E={}", endToEndId, e);
            return false;
        }
    }
    
    /**
     * Get transaction by end_to_end_id.
     * 
     * @param endToEndId End-to-end transaction identifier
     * @return TransactionDocument or null if not found
     */
    public TransactionDocument getTransaction(String endToEndId) {
        return transactionStore.get(endToEndId);
    }
    
    /**
     * Clear all transactions (for testing only).
     */
    public void clear() {
        transactionStore.clear();
        log.debug("Cleared all transactions from store");
    }
}

