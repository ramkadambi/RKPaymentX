package com.wellsfargo.payment.cancellation;

import java.time.Instant;

/**
 * Represents a cancellation case for tracking investigations.
 * 
 * This tracks the two-layer process:
 * 1. Investigation Layer: camt.056 → camt.029 (decision)
 * 2. Settlement Layer: pacs.004 (actual fund return)
 */
public class CancellationCase {
    
    private String caseId;
    private String originalEndToEndId;
    private String originalMsgId;
    private String originalCamt056MsgId;
    private String originalInstrId;
    private String originalTxId;
    private String reasonCode;
    private String additionalInfo;
    private CaseStatus status;
    private String resolutionMsgId; // camt.029 message ID
    private String returnMsgId; // pacs.004 message ID (if approved)
    private Instant createdAt;
    private Instant resolvedAt;
    private Instant returnedAt;
    private String ingressChannel; // SWIFT, FED, CHIPS, WELLS
    private String instructingAgentBic; // BIC of bank requesting cancellation
    private String returnAmount; // Amount to be returned (may include fees)
    private String returnCurrency;
    private String returnFees; // Processing fees (if any)
    
    /**
     * Case status for tracking investigation progress.
     */
    public enum CaseStatus {
        /** Case created, investigation started */
        PENDING,
        /** Pending Cancellation Request - waiting for customer approval */
        PDCR,
        /** Refused - refund cannot be processed */
        REFUSED,
        /** Approved - refund will be processed */
        APPROVED,
        /** Return completed - pacs.004 sent */
        RETURNED
    }
    
    public CancellationCase() {
        this.createdAt = Instant.now();
        this.status = CaseStatus.PENDING;
    }
    
    public CancellationCase(String caseId, String originalEndToEndId, 
                            String originalCamt056MsgId, String reasonCode) {
        this();
        this.caseId = caseId;
        this.originalEndToEndId = originalEndToEndId;
        this.originalCamt056MsgId = originalCamt056MsgId;
        this.reasonCode = reasonCode;
    }
    
    // Getters and setters
    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    
    public String getOriginalEndToEndId() { return originalEndToEndId; }
    public void setOriginalEndToEndId(String originalEndToEndId) { 
        this.originalEndToEndId = originalEndToEndId; 
    }
    
    public String getOriginalMsgId() { return originalMsgId; }
    public void setOriginalMsgId(String originalMsgId) { 
        this.originalMsgId = originalMsgId; 
    }
    
    public String getOriginalCamt056MsgId() { return originalCamt056MsgId; }
    public void setOriginalCamt056MsgId(String originalCamt056MsgId) { 
        this.originalCamt056MsgId = originalCamt056MsgId; 
    }
    
    public String getOriginalInstrId() { return originalInstrId; }
    public void setOriginalInstrId(String originalInstrId) { 
        this.originalInstrId = originalInstrId; 
    }
    
    public String getOriginalTxId() { return originalTxId; }
    public void setOriginalTxId(String originalTxId) { 
        this.originalTxId = originalTxId; 
    }
    
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { 
        this.reasonCode = reasonCode; 
    }
    
    public String getAdditionalInfo() { return additionalInfo; }
    public void setAdditionalInfo(String additionalInfo) { 
        this.additionalInfo = additionalInfo; 
    }
    
    public CaseStatus getStatus() { return status; }
    public void setStatus(CaseStatus status) { 
        this.status = status; 
        if (status == CaseStatus.REFUSED || status == CaseStatus.APPROVED) {
            this.resolvedAt = Instant.now();
        }
        if (status == CaseStatus.RETURNED) {
            this.returnedAt = Instant.now();
        }
    }
    
    public String getResolutionMsgId() { return resolutionMsgId; }
    public void setResolutionMsgId(String resolutionMsgId) { 
        this.resolutionMsgId = resolutionMsgId; 
    }
    
    public String getReturnMsgId() { return returnMsgId; }
    public void setReturnMsgId(String returnMsgId) { 
        this.returnMsgId = returnMsgId; 
    }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    
    public Instant getReturnedAt() { return returnedAt; }
    public void setReturnedAt(Instant returnedAt) { this.returnedAt = returnedAt; }
    
    public String getIngressChannel() { return ingressChannel; }
    public void setIngressChannel(String ingressChannel) { 
        this.ingressChannel = ingressChannel; 
    }
    
    public String getInstructingAgentBic() { return instructingAgentBic; }
    public void setInstructingAgentBic(String instructingAgentBic) { 
        this.instructingAgentBic = instructingAgentBic; 
    }
    
    public String getReturnAmount() { return returnAmount; }
    public void setReturnAmount(String returnAmount) { 
        this.returnAmount = returnAmount; 
    }
    
    public String getReturnCurrency() { return returnCurrency; }
    public void setReturnCurrency(String returnCurrency) { 
        this.returnCurrency = returnCurrency; 
    }
    
    public String getReturnFees() { return returnFees; }
    public void setReturnFees(String returnFees) { 
        this.returnFees = returnFees; 
    }
}

