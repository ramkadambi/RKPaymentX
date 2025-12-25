package com.wellsfargo.payment.error;

import com.wellsfargo.payment.canonical.PaymentEvent;
import com.wellsfargo.payment.canonical.ServiceResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Error record for payment processing errors.
 * 
 * Represents an error that occurred during payment processing,
 * including the payment event, service result, and error context.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorRecord {
    
    /**
     * Unique error record ID.
     */
    private String errorId;
    
    /**
     * End-to-end ID of the payment.
     */
    private String endToEndId;
    
    /**
     * Payment event at the time of error.
     */
    private PaymentEvent paymentEvent;
    
    /**
     * Service result containing error details.
     */
    private ServiceResult serviceResult;
    
    /**
     * Service name where error occurred.
     */
    private String serviceName;
    
    /**
     * Error topic where this error was published.
     */
    private String errorTopic;
    
    /**
     * Timestamp when error occurred.
     */
    private String errorTimestamp;
    
    /**
     * Last successful step before error.
     */
    private String lastSuccessfulStep;
    
    /**
     * Processing state at time of error.
     */
    private String processingState;
    
    /**
     * Error severity (LOW, MEDIUM, HIGH, CRITICAL).
     */
    private ErrorSeverity severity;
    
    /**
     * Error category (TECHNICAL, BUSINESS, OPERATIONAL).
     */
    private ErrorCategory category;
    
    /**
     * Error status (NEW, IN_PROGRESS, FIXED, CANCELLED).
     */
    private ErrorStatus status;
    
    /**
     * Additional error context/metadata.
     */
    private String errorContext;
    
    /**
     * User who is handling this error (if assigned).
     */
    private String assignedTo;
    
    /**
     * Timestamp when error was created.
     */
    private String createdAt;
    
    /**
     * Timestamp when error was last updated.
     */
    private String updatedAt;
    
    /**
     * Error severity levels.
     */
    public enum ErrorSeverity {
        LOW, MEDIUM, HIGH, CRITICAL
    }
    
    /**
     * Error categories.
     */
    public enum ErrorCategory {
        TECHNICAL,    // System/technical errors
        BUSINESS,        // Business rule violations
        OPERATIONAL      // Operational issues
    }
    
    /**
     * Error status.
     */
    public enum ErrorStatus {
        NEW,            // New error, not yet handled
        IN_PROGRESS,    // Being worked on
        FIXED,          // Fixed and resumed
        CANCELLED,      // Payment cancelled
        RESOLVED        // Resolved (restarted or cancelled)
    }
}

