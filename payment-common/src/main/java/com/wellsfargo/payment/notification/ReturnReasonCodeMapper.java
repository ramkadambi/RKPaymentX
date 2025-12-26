package com.wellsfargo.payment.notification;

import com.wellsfargo.payment.canonical.enums.ReturnReasonCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mapper to convert UI cancellation reasons to ISO 20022 return reason codes.
 * 
 * Maps user-friendly cancellation reasons from the Error Management UI
 * to standard ISO 20022 return reason codes used in PACS.004 messages.
 */
public class ReturnReasonCodeMapper {
    
    private static final Logger log = LoggerFactory.getLogger(ReturnReasonCodeMapper.class);
    
    /**
     * Map UI cancellation reason to ISO 20022 return reason code.
     * 
     * @param uiCancellationReason UI cancellation reason string
     * @return ISO 20022 return reason code
     */
    public static ReturnReasonCode mapToIso20022Code(String uiCancellationReason) {
        if (uiCancellationReason == null || uiCancellationReason.isEmpty()) {
            log.warn("Empty cancellation reason provided, defaulting to NARR");
            return ReturnReasonCode.NARR;
        }
        
        String reason = uiCancellationReason.toLowerCase().trim();
        
        // Map UI reasons to ISO 20022 codes
        switch (reason) {
            case "business_rule":
            case "transaction_forbidden":
                // Business rule violation or transaction not allowed
                return ReturnReasonCode.AG01; // Transaction forbidden
                
            case "sanctions":
            case "sanctions_hit":
            case "aml":
                // Sanctions or AML related
                return ReturnReasonCode.RR01; // Regulatory reason
                
            case "insufficient_funds":
            case "insufficient_balance":
                // Insufficient funds in debtor account
                return ReturnReasonCode.AM04; // Insufficient funds
                
            case "invalid_account":
            case "account_identifier_incorrect":
                // Invalid account identifier
                return ReturnReasonCode.AC01; // Account identifier incorrect
                
            case "account_closed":
                // Beneficiary account closed
                return ReturnReasonCode.AC04; // Account closed
                
            case "account_blocked":
            case "account_frozen":
                // Account blocked or frozen
                return ReturnReasonCode.AC06; // Account blocked
                
            case "duplicate_payment":
            case "duplicate":
                // Duplicate payment detected
                return ReturnReasonCode.AM05; // Duplicate payment
                
            case "incorrect_creditor_name":
            case "name_mismatch":
                // Creditor name mismatch
                return ReturnReasonCode.BE04; // Incorrect creditor name
                
            case "creditor_missing":
            case "missing_beneficiary":
                // Creditor details missing
                return ReturnReasonCode.BE05; // Creditor missing
                
            case "invalid_bank_identifier":
            case "wrong_bic":
                // Invalid bank identifier
                return ReturnReasonCode.RC01; // Invalid bank identifier
                
            case "regulatory_reporting":
            case "missing_compliance_data":
                // Regulatory reporting incomplete
                return ReturnReasonCode.RR02; // Regulatory reporting incomplete
                
            case "invalid_bank_operation":
            case "operation_not_permitted":
                // Invalid bank operation
                return ReturnReasonCode.AG02; // Invalid bank operation
                
            case "customer_request":
            case "other":
            default:
                // Default to narrative reason for customer requests or unknown reasons
                log.info("Using NARR (Narrative reason) for cancellation reason: {}", uiCancellationReason);
                return ReturnReasonCode.NARR; // Narrative reason
        }
    }
    
    /**
     * Get description for a return reason code.
     */
    public static String getDescription(ReturnReasonCode code) {
        return code.getDescription();
    }
}

