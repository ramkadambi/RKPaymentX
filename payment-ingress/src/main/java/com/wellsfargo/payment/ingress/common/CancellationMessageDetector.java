package com.wellsfargo.payment.ingress.common;

/**
 * Detects cancellation message types (camt.055, camt.056) in incoming messages.
 * 
 * Used by ingress services to route cancellation messages to the appropriate handler.
 */
public class CancellationMessageDetector {
    
    /**
     * Detect if a message is a cancellation message and what type.
     */
    public static CancellationMessageType detectCancellationMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            return CancellationMessageType.NONE;
        }
        
        String upperMessage = message.toUpperCase();
        
        // Check for camt.055 (Customer Payment Cancellation Request)
        if (upperMessage.contains("CSTMTPMTCXLREQ") || 
            upperMessage.contains("CSTMRPMTCXLREQ") ||
            upperMessage.contains("CUSTOMERPAYMENTCANCELLATIONREQUEST") ||
            (upperMessage.contains("CAMT.055") || upperMessage.contains("CAMT055")) ||
            (upperMessage.contains("CANCEL") && upperMessage.contains("CUSTOMER"))) {
            return CancellationMessageType.CAMT055;
        }
        
        // Check for camt.056 (FI-to-FI Payment Cancellation Request)
        if (upperMessage.contains("FITOFIPMTCXLREQ") ||
            upperMessage.contains("FITO-FIPMTCXLREQ") ||
            upperMessage.contains("FITOFIPAYMENTCANCELLATIONREQUEST") ||
            (upperMessage.contains("CAMT.056") || upperMessage.contains("CAMT056")) ||
            (upperMessage.contains("CANCEL") && upperMessage.contains("FI"))) {
            return CancellationMessageType.CAMT056;
        }
        
        // Check XML namespace
        if (message.contains("urn:iso:std:iso:20022:tech:xsd:camt.055")) {
            return CancellationMessageType.CAMT055;
        }
        if (message.contains("urn:iso:std:iso:20022:tech:xsd:camt.056")) {
            return CancellationMessageType.CAMT056;
        }
        
        return CancellationMessageType.NONE;
    }
    
    /**
     * Cancellation message types.
     */
    public enum CancellationMessageType {
        /** Not a cancellation message */
        NONE,
        /** camt.055 - Customer Payment Cancellation Request */
        CAMT055,
        /** camt.056 - FI-to-FI Payment Cancellation Request */
        CAMT056
    }
}

