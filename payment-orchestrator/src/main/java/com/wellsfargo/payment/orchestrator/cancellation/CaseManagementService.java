package com.wellsfargo.payment.orchestrator.cancellation;

import com.wellsfargo.payment.cancellation.CancellationCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Case Management Service for tracking cancellation investigations.
 * 
 * This service tracks the two-layer process:
 * 1. Investigation Layer: camt.056 → camt.029 (decision)
 * 2. Settlement Layer: pacs.004 (actual fund return)
 * 
 * In production, this would be backed by a database. For now, uses in-memory storage.
 */
public class CaseManagementService {
    
    private static final Logger log = LoggerFactory.getLogger(CaseManagementService.class);
    
    // In-memory case storage (in production, use database)
    private final Map<String, CancellationCase> casesByCaseId = new ConcurrentHashMap<>();
    private final Map<String, CancellationCase> casesByEndToEndId = new ConcurrentHashMap<>();
    private final Map<String, CancellationCase> casesByCamt056MsgId = new ConcurrentHashMap<>();
    
    /**
     * Create a new cancellation case from camt.056 request.
     * 
     * @param originalEndToEndId Original payment's end-to-end ID
     * @param originalCamt056MsgId Original camt.056 message ID
     * @param originalInstrId Original instruction ID
     * @param originalTxId Original transaction ID
     * @param reasonCode Cancellation reason code
     * @param additionalInfo Additional information
     * @param ingressChannel Ingress channel (SWIFT, FED, CHIPS, WELLS)
     * @param instructingAgentBic BIC of bank requesting cancellation
     * @return Created CancellationCase
     */
    public CancellationCase createCase(String originalEndToEndId, String originalCamt056MsgId,
                                      String originalInstrId, String originalTxId,
                                      String reasonCode, String additionalInfo,
                                      String ingressChannel, String instructingAgentBic) {
        String caseId = generateCaseId();
        
        CancellationCase cancellationCase = new CancellationCase();
        cancellationCase.setCaseId(caseId);
        cancellationCase.setOriginalEndToEndId(originalEndToEndId);
        cancellationCase.setOriginalCamt056MsgId(originalCamt056MsgId);
        cancellationCase.setOriginalInstrId(originalInstrId);
        cancellationCase.setOriginalTxId(originalTxId);
        cancellationCase.setReasonCode(reasonCode);
        cancellationCase.setAdditionalInfo(additionalInfo);
        cancellationCase.setIngressChannel(ingressChannel);
        cancellationCase.setInstructingAgentBic(instructingAgentBic);
        cancellationCase.setStatus(CancellationCase.CaseStatus.PENDING);
        cancellationCase.setCreatedAt(Instant.now());
        
        // Store case
        casesByCaseId.put(caseId, cancellationCase);
        casesByEndToEndId.put(originalEndToEndId, cancellationCase);
        casesByCamt056MsgId.put(originalCamt056MsgId, cancellationCase);
        
        log.info("Created cancellation case - CaseId={}, E2E={}, Channel={}", 
            caseId, originalEndToEndId, ingressChannel);
        
        return cancellationCase;
    }
    
    /**
     * Get case by case ID.
     */
    public CancellationCase getCaseByCaseId(String caseId) {
        return casesByCaseId.get(caseId);
    }
    
    /**
     * Get case by end-to-end ID.
     */
    public CancellationCase getCaseByEndToEndId(String endToEndId) {
        return casesByEndToEndId.get(endToEndId);
    }
    
    /**
     * Get case by camt.056 message ID.
     */
    public CancellationCase getCaseByCamt056MsgId(String camt056MsgId) {
        return casesByCamt056MsgId.get(camt056MsgId);
    }
    
    /**
     * Update case status to PDCR (Pending Cancellation Request).
     */
    public void setCasePending(CancellationCase cancellationCase) {
        cancellationCase.setStatus(CancellationCase.CaseStatus.PDCR);
        log.info("Case status updated to PDCR - CaseId={}, E2E={}", 
            cancellationCase.getCaseId(), cancellationCase.getOriginalEndToEndId());
    }
    
    /**
     * Update case status to REFUSED and record resolution message ID.
     */
    public void setCaseRefused(CancellationCase cancellationCase, String resolutionMsgId) {
        cancellationCase.setStatus(CancellationCase.CaseStatus.REFUSED);
        cancellationCase.setResolutionMsgId(resolutionMsgId);
        cancellationCase.setResolvedAt(Instant.now());
        log.info("Case status updated to REFUSED - CaseId={}, E2E={}, ResolutionMsgId={}", 
            cancellationCase.getCaseId(), cancellationCase.getOriginalEndToEndId(), resolutionMsgId);
    }
    
    /**
     * Update case status to APPROVED and record resolution message ID.
     */
    public void setCaseApproved(CancellationCase cancellationCase, String resolutionMsgId,
                               String returnAmount, String returnCurrency, String returnFees) {
        cancellationCase.setStatus(CancellationCase.CaseStatus.APPROVED);
        cancellationCase.setResolutionMsgId(resolutionMsgId);
        cancellationCase.setReturnAmount(returnAmount);
        cancellationCase.setReturnCurrency(returnCurrency);
        cancellationCase.setReturnFees(returnFees);
        cancellationCase.setResolvedAt(Instant.now());
        log.info("Case status updated to APPROVED - CaseId={}, E2E={}, ResolutionMsgId={}, ReturnAmount={}", 
            cancellationCase.getCaseId(), cancellationCase.getOriginalEndToEndId(), 
            resolutionMsgId, returnAmount);
    }
    
    /**
     * Update case status to RETURNED and record return message ID.
     */
    public void setCaseReturned(CancellationCase cancellationCase, String returnMsgId) {
        cancellationCase.setStatus(CancellationCase.CaseStatus.RETURNED);
        cancellationCase.setReturnMsgId(returnMsgId);
        cancellationCase.setReturnedAt(Instant.now());
        log.info("Case status updated to RETURNED - CaseId={}, E2E={}, ReturnMsgId={}", 
            cancellationCase.getCaseId(), cancellationCase.getOriginalEndToEndId(), returnMsgId);
    }
    
    /**
     * Generate unique case ID.
     */
    private String generateCaseId() {
        String prefix = "CASE-";
        String timestamp = Instant.now().toString().replaceAll("[^0-9]", "").substring(0, 14);
        String uniqueId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return prefix + timestamp + "-" + uniqueId;
    }
}

