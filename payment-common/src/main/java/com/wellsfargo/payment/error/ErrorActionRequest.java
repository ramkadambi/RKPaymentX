package com.wellsfargo.payment.error;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request for error actions (fix, restart, cancel).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorActionRequest {
    
    /**
     * Error record ID.
     */
    private String errorId;
    
    /**
     * Action type.
     */
    private ActionType actionType;
    
    /**
     * Fix details (for FIX_AND_RESUME action).
     */
    private FixDetails fixDetails;
    
    /**
     * Cancellation reason (for CANCEL action).
     */
    private String cancellationReason;
    
    /**
     * User performing the action.
     */
    private String performedBy;
    
    /**
     * Additional comments.
     */
    private String comments;
    
    /**
     * Action types.
     */
    public enum ActionType {
        FIX_AND_RESUME,      // Fix error and resume from failed step
        RESTART_FROM_BEGINNING, // Restart from account validation
        CANCEL_AND_RETURN   // Cancel payment and return funds
    }
    
    /**
     * Fix details for FIX_AND_RESUME action.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FixDetails {
        /**
         * Type of fix applied.
         */
        private String fixType;
        
        /**
         * Fixed payment event (if payment data was corrected).
         */
        private String fixedPaymentEventJson;
        
        /**
         * Override reason (if business rule was overridden).
         */
        private String overrideReason;
        
        /**
         * Additional fix metadata.
         */
        private String fixMetadata;
    }
}

