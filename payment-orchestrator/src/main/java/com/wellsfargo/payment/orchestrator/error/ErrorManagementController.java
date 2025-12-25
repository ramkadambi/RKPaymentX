package com.wellsfargo.payment.orchestrator.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wellsfargo.payment.canonical.PaymentEvent;
import com.wellsfargo.payment.error.ErrorActionRequest;
import com.wellsfargo.payment.error.ErrorRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST Controller for Error Management UI.
 */
@RestController
@RequestMapping("/api/errors")
@CrossOrigin(origins = "*") // Allow CORS for UI
public class ErrorManagementController {
    
    private static final Logger log = LoggerFactory.getLogger(ErrorManagementController.class);
    
    @Autowired
    private ErrorManagementService errorManagementService;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    /**
     * Get all errors.
     */
    @GetMapping
    public ResponseEntity<List<ErrorRecord>> getAllErrors(
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String endToEndId) {
        
        try {
            List<ErrorRecord> errors;
            
            if (endToEndId != null && !endToEndId.isEmpty()) {
                errors = errorManagementService.getErrorsByEndToEndId(endToEndId);
            } else if (service != null && !service.isEmpty()) {
                errors = errorManagementService.getErrorsByService(service);
            } else if (status != null && !status.isEmpty()) {
                try {
                    ErrorRecord.ErrorStatus errorStatus = ErrorRecord.ErrorStatus.valueOf(status);
                    errors = errorManagementService.getErrorsByStatus(errorStatus);
                } catch (IllegalArgumentException e) {
                    errors = errorManagementService.getAllErrors();
                }
            } else {
                errors = errorManagementService.getAllErrors();
            }
            
            return ResponseEntity.ok(errors);
        } catch (Exception e) {
            log.error("Error getting errors", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * Get error by ID.
     */
    @GetMapping("/{errorId}")
    public ResponseEntity<ErrorRecord> getError(@PathVariable String errorId) {
        try {
            ErrorRecord error = errorManagementService.getError(errorId);
            if (error == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(error);
        } catch (Exception e) {
            log.error("Error getting error: {}", errorId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * Get error statistics.
     */
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getStatistics() {
        try {
            List<ErrorRecord> allErrors = errorManagementService.getAllErrors();
            
            long total = allErrors.size();
            long today = allErrors.stream()
                .filter(e -> e.getCreatedAt() != null && 
                    e.getCreatedAt().startsWith(java.time.LocalDate.now().toString()))
                .count();
            long high = allErrors.stream()
                .filter(e -> e.getSeverity() == ErrorRecord.ErrorSeverity.HIGH ||
                           e.getSeverity() == ErrorRecord.ErrorSeverity.CRITICAL)
                .count();
            long newErrors = allErrors.stream()
                .filter(e -> e.getStatus() == ErrorRecord.ErrorStatus.NEW)
                .count();
            
            Map<String, Object> stats = Map.of(
                "total", total,
                "today", today,
                "high", high,
                "new", newErrors
            );
            
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("Error getting statistics", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * Fix and resume from failed step.
     */
    @PostMapping("/{errorId}/fix-and-resume")
    public ResponseEntity<Map<String, String>> fixAndResume(
            @PathVariable String errorId,
            @RequestBody Map<String, Object> requestBody) {
        
        try {
            ErrorRecord error = errorManagementService.getError(errorId);
            if (error == null) {
                return ResponseEntity.notFound().build();
            }
            
            // Parse payment event from request
            PaymentEvent paymentEvent = null;
            if (requestBody.containsKey("paymentEvent")) {
                paymentEvent = objectMapper.convertValue(
                    requestBody.get("paymentEvent"), PaymentEvent.class);
            } else {
                // Use payment event from error record if available
                paymentEvent = error.getPaymentEvent();
                // If still null, we need to get it from orchestrator cache
                // For now, return error - in production, fetch from orchestrator or database
                if (paymentEvent == null) {
                    return ResponseEntity.badRequest()
                        .body(Map.of("error", "Payment event not found. Please provide paymentEvent in request body."));
                }
            }
            
            // Create action request
            ErrorActionRequest actionRequest = ErrorActionRequest.builder()
                .errorId(errorId)
                .actionType(ErrorActionRequest.ActionType.FIX_AND_RESUME)
                .performedBy((String) requestBody.getOrDefault("performedBy", "system"))
                .comments((String) requestBody.get("comments"))
                .build();
            
            errorManagementService.fixAndResume(actionRequest, paymentEvent);
            
            return ResponseEntity.ok(Map.of(
                "message", "Payment fixed and resumed",
                "errorId", errorId
            ));
        } catch (Exception e) {
            log.error("Error fixing and resuming: {}", errorId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * Restart from beginning.
     */
    @PostMapping("/{errorId}/restart")
    public ResponseEntity<Map<String, String>> restartFromBeginning(
            @PathVariable String errorId,
            @RequestBody Map<String, Object> requestBody) {
        
        try {
            ErrorRecord error = errorManagementService.getError(errorId);
            if (error == null) {
                return ResponseEntity.notFound().build();
            }
            
            // Parse payment event from request
            PaymentEvent paymentEvent = null;
            if (requestBody.containsKey("paymentEvent")) {
                paymentEvent = objectMapper.convertValue(
                    requestBody.get("paymentEvent"), PaymentEvent.class);
            } else {
                paymentEvent = error.getPaymentEvent();
            }
            
            if (paymentEvent == null) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Payment event not found"));
            }
            
            // Create action request
            ErrorActionRequest actionRequest = ErrorActionRequest.builder()
                .errorId(errorId)
                .actionType(ErrorActionRequest.ActionType.RESTART_FROM_BEGINNING)
                .performedBy((String) requestBody.getOrDefault("performedBy", "system"))
                .comments((String) requestBody.get("comments"))
                .build();
            
            errorManagementService.restartFromBeginning(actionRequest, paymentEvent);
            
            return ResponseEntity.ok(Map.of(
                "message", "Payment restarted from beginning",
                "errorId", errorId
            ));
        } catch (Exception e) {
            log.error("Error restarting: {}", errorId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * Cancel and return payment.
     */
    @PostMapping("/{errorId}/cancel")
    public ResponseEntity<Map<String, String>> cancelAndReturn(
            @PathVariable String errorId,
            @RequestBody Map<String, Object> requestBody) {
        
        try {
            ErrorRecord error = errorManagementService.getError(errorId);
            if (error == null) {
                return ResponseEntity.notFound().build();
            }
            
            PaymentEvent paymentEvent = error.getPaymentEvent();
            if (paymentEvent == null) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Payment event not found"));
            }
            
            // Create action request
            ErrorActionRequest actionRequest = ErrorActionRequest.builder()
                .errorId(errorId)
                .actionType(ErrorActionRequest.ActionType.CANCEL_AND_RETURN)
                .cancellationReason((String) requestBody.get("cancellationReason"))
                .performedBy((String) requestBody.getOrDefault("performedBy", "system"))
                .comments((String) requestBody.get("comments"))
                .build();
            
            errorManagementService.cancelAndReturn(actionRequest, paymentEvent);
            
            return ResponseEntity.ok(Map.of(
                "message", "Payment cancelled and returned",
                "errorId", errorId
            ));
        } catch (Exception e) {
            log.error("Error cancelling: {}", errorId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }
}

