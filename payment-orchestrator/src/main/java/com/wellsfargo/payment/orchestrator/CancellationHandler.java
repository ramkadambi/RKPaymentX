package com.wellsfargo.payment.orchestrator;

import com.wellsfargo.payment.canonical.PaymentEvent;
import com.wellsfargo.payment.canonical.enums.Pacs002Status;
import com.wellsfargo.payment.canonical.enums.ReturnReasonCode;
import com.wellsfargo.payment.cancellation.CancellationCase;
import com.wellsfargo.payment.notification.Camt029Generator;
import com.wellsfargo.payment.notification.NotificationService;
import com.wellsfargo.payment.orchestrator.cancellation.CaseManagementService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cancellation Handler for processing camt.056 cancellation requests with two-layer process.
 * 
 * This service implements the full return of funds process:
 * 
 * Investigation Layer (The "Talk"):
 * - Receives camt.056 cancellation requests from payments.cancellation.in
 * - Creates a cancellation case for tracking
 * - Validates payment status and business rules
 * - Sends camt.029 (Resolution of Investigation) with decision:
 *   - RJCT: Refused (e.g., goods already shipped)
 *   - PDCR: Pending Cancellation Request (waiting for customer approval)
 *   - ACCP: Approved (will proceed to settlement layer)
 * 
 * Settlement Layer (The "Action"):
 * - If approved, generates pacs.004 (Payment Return) to actually move funds
 * - Handles fees and settlement dates
 * - Links to original payment via UETR/EndToEndId
 * 
 * Disabled in mock mode (error.management.mock.mode=true) since it requires Kafka.
 */
@Service
@ConditionalOnProperty(name = "error.management.mock.mode", havingValue = "false", matchIfMissing = true)
public class CancellationHandler {
    
    private static final Logger log = LoggerFactory.getLogger(CancellationHandler.class);
    private static final String TOPIC_CANCELLATION_IN = "payments.cancellation.in";
    
    @Value("${kafka.bootstrap.servers:localhost:9092}")
    private String bootstrapServers;
    
    @Value("${kafka.consumer.group-id:cancellation-handler-group}")
    private String groupId;
    
    @Value("${kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;
    
    @Value("${cancellation.return.fee:0.00}")
    private String defaultReturnFee;
    
    private KafkaConsumer<String, String> consumer;
    private NotificationService notificationService;
    private CaseManagementService caseManagementService;
    private Camt029Generator camt029Generator;
    private PaymentOrchestratorService orchestratorService;
    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    /**
     * Set orchestrator service for accessing payment events.
     * Called after orchestrator is initialized.
     */
    public void setOrchestratorService(PaymentOrchestratorService orchestratorService) {
        this.orchestratorService = orchestratorService;
    }
    
    @PostConstruct
    public void init() {
        log.info("Initializing Cancellation Handler with two-layer process support");
        
        // Initialize services
        notificationService = new NotificationService(bootstrapServers);
        caseManagementService = new CaseManagementService();
        camt029Generator = new Camt029Generator();
        
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        
        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singletonList(TOPIC_CANCELLATION_IN));
        
        running.set(true);
        executorService = Executors.newSingleThreadExecutor();
        executorService.submit(this::processCancellationRequests);
        
        log.info("Cancellation Handler initialized and started");
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down Cancellation Handler");
        
        running.set(false);
        
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        if (consumer != null) {
            consumer.close();
        }
        
        log.info("Cancellation Handler shut down");
    }
    
    /**
     * Process cancellation requests from Kafka.
     */
    private void processCancellationRequests() {
        log.info("Starting cancellation request processing thread");
        
        while (running.get()) {
            try {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
                
                for (ConsumerRecord<String, String> record : records) {
                    try {
                        String camt056Xml = record.value();
                        if (camt056Xml != null) {
                            handleCancellationRequest(camt056Xml);
                            consumer.commitSync();
                        }
                    } catch (Exception e) {
                        log.error("Error processing cancellation request: {}", record.key(), e);
                    }
                }
            } catch (Exception e) {
                log.error("Error in cancellation request processing loop", e);
            }
        }
        
        log.info("Cancellation request processing thread stopped");
    }
    
    /**
     * Handle camt.056 cancellation request with two-layer process.
     * 
     * Layer 1 (Investigation): Create case, investigate, send camt.029
     * Layer 2 (Settlement): If approved, send pacs.004 to return funds
     */
    private void handleCancellationRequest(String camt056Xml) {
        try {
            // Parse camt.056 XML to extract cancellation details
            CancellationRequest request = parseCamt056(camt056Xml);
            
            String endToEndId = request.getOriginalEndToEndId();
            String originalInstrId = request.getOriginalInstrId();
            String originalCamt056MsgId = extractCamt056MsgId(camt056Xml);
            String ingressChannel = extractIngressChannel(camt056Xml);
            String instructingAgentBic = extractInstructingAgentBic(camt056Xml);
            
            log.info("Processing cancellation request (two-layer) - E2E={}, InstrId={}, Channel={}", 
                endToEndId, originalInstrId, ingressChannel);
            
            // Get payment event
            PaymentEvent event = null;
            if (orchestratorService != null) {
                event = orchestratorService.getPaymentEvent(endToEndId);
            }
            
            if (event == null) {
                log.warn("Payment not found for cancellation - E2E={}", endToEndId);
                // Still create case and send refusal
                handlePaymentNotFound(endToEndId, originalCamt056MsgId, originalInstrId, 
                    request.getReasonCode(), request.getAdditionalInfo(), ingressChannel, instructingAgentBic);
                return;
            }
            
            // Create cancellation case
            CancellationCase cancellationCase = caseManagementService.createCase(
                endToEndId, originalCamt056MsgId, originalInstrId, request.getOriginalTxId(),
                request.getReasonCode(), request.getAdditionalInfo(), ingressChannel, instructingAgentBic);
            
            cancellationCase.setOriginalMsgId(event.getMsgId());
            
            // Check payment status to determine if it's already settled
            String processingState = event.getProcessingState();
            boolean isSettled = processingState != null && 
                (processingState.equals("COMPLETED") || processingState.equals("SETTLED") || 
                 processingState.equals("ACSC"));
            
            if (isSettled) {
                // Payment already settled - need to investigate and potentially return funds
                log.info("Payment already settled - initiating return of funds investigation - E2E={}, CaseId={}", 
                    endToEndId, cancellationCase.getCaseId());
                handleSettledPaymentCancellation(cancellationCase, event, request);
            } else {
                // Payment not yet settled - can cancel directly
                log.info("Payment not yet settled - cancelling directly - E2E={}, CaseId={}", 
                    endToEndId, cancellationCase.getCaseId());
                handleUnsettledPaymentCancellation(cancellationCase, event);
            }
            
        } catch (Exception e) {
            log.error("Error handling cancellation request", e);
        }
    }
    
    /**
     * Handle cancellation for payment that is NOT yet settled.
     * Simple cancellation - no fund return needed.
     */
    private void handleUnsettledPaymentCancellation(CancellationCase cancellationCase, PaymentEvent event) {
        // Remove from orchestrator cache to prevent further processing
        if (orchestratorService != null) {
            orchestratorService.removePaymentEvent(event.getEndToEndId());
        }
        
        // Send CANC status via PACS.002 (no camt.029 needed for simple cancellation)
        notificationService.publishStatus(event, Pacs002Status.CANC, 
            "CANC", "Payment cancelled as requested");
        
        log.info("Payment cancelled successfully (unsettled) - E2E={}, CaseId={}", 
            event.getEndToEndId(), cancellationCase.getCaseId());
    }
    
    /**
     * Handle cancellation for payment that IS already settled.
     * Requires two-layer process: investigation → settlement.
     */
    private void handleSettledPaymentCancellation(CancellationCase cancellationCase, PaymentEvent event, 
                                                  CancellationRequest request) {
        // Layer 1: Investigation - determine if refund is possible
        Camt029Generator.ResolutionStatus resolutionStatus = determineRefundResolution(
            cancellationCase, event, request);
        
        // Generate and send camt.029 (Resolution of Investigation)
        String camt029Xml = camt029Generator.generateCamt029(
            event, cancellationCase.getOriginalCamt056MsgId(), cancellationCase.getCaseId(),
            resolutionStatus, request.getReasonCode(), request.getAdditionalInfo());
        
        String resolutionMsgId = extractMessageId(camt029Xml);
        cancellationCase.setResolutionMsgId(resolutionMsgId);
        
        // Publish camt.029
        notificationService.publishCamt029(camt029Xml, event.getEndToEndId());
        
        if (resolutionStatus == Camt029Generator.ResolutionStatus.RJCT) {
            // Refund refused
            caseManagementService.setCaseRefused(cancellationCase, resolutionMsgId);
            log.info("Refund refused - E2E={}, CaseId={}, Reason={}", 
                event.getEndToEndId(), cancellationCase.getCaseId(), request.getReasonCode());
            
        } else if (resolutionStatus == Camt029Generator.ResolutionStatus.PDCR) {
            // Pending - waiting for customer approval
            caseManagementService.setCasePending(cancellationCase);
            log.info("Refund pending customer approval - E2E={}, CaseId={}", 
                event.getEndToEndId(), cancellationCase.getCaseId());
            
        } else if (resolutionStatus == Camt029Generator.ResolutionStatus.ACCP) {
            // Refund approved - proceed to Layer 2: Settlement
            log.info("Refund approved - proceeding to settlement layer - E2E={}, CaseId={}", 
                event.getEndToEndId(), cancellationCase.getCaseId());
            
            // Calculate return amount (original amount minus fees if applicable)
            BigDecimal originalAmount = event.getAmount() != null ? event.getAmount() : BigDecimal.ZERO;
            BigDecimal returnFee = new BigDecimal(defaultReturnFee);
            BigDecimal returnAmount = originalAmount.subtract(returnFee);
            
            // Update case with return details
            caseManagementService.setCaseApproved(cancellationCase, resolutionMsgId,
                returnAmount.toString(), event.getCurrency() != null ? event.getCurrency() : "USD",
                returnFee.toString());
            
            // Layer 2: Settlement - generate and send pacs.004 (Payment Return)
            // Map cancellation reason code to return reason code
            ReturnReasonCode returnReasonCode = mapCancellationReasonToReturnReason(request.getReasonCode());
            String settlementDate = Instant.now().toString();
            notificationService.publishPaymentReturnWithFees(event, 
                returnReasonCode,
                "Payment returned as requested: " + request.getAdditionalInfo(),
                returnAmount.toString(), returnFee.toString(), settlementDate);
            
            String returnMsgId = extractMessageId(notificationService.getLastPacs004Xml());
            caseManagementService.setCaseReturned(cancellationCase, returnMsgId);
            
            log.info("Funds returned successfully - E2E={}, CaseId={}, ReturnAmount={}, Fees={}", 
                event.getEndToEndId(), cancellationCase.getCaseId(), returnAmount, returnFee);
        }
    }
    
    /**
     * Determine refund resolution based on business rules.
     * In production, this would involve customer validation, business rule checks, etc.
     */
    private Camt029Generator.ResolutionStatus determineRefundResolution(
        CancellationCase cancellationCase, PaymentEvent event, CancellationRequest request) {
        
        // Business rule: Check reason code
        String reasonCode = request.getReasonCode();
        
        // If reason is DUPL (duplicate) or FRAD (fraud), typically refuse
        if ("DUPL".equals(reasonCode) || "FRAD".equals(reasonCode)) {
            // Verify with customer - for now, assume refused
            return Camt029Generator.ResolutionStatus.RJCT;
        }
        
        // If reason is CUST (customer request), typically approve
        if ("CUST".equals(reasonCode)) {
            // In production, would check with customer/merchant
            // For now, assume approved
            return Camt029Generator.ResolutionStatus.ACCP;
        }
        
        // Default: Pending (requires manual review)
        return Camt029Generator.ResolutionStatus.PDCR;
    }
    
    /**
     * Map cancellation reason code to return reason code for pacs.004.
     */
    private ReturnReasonCode mapCancellationReasonToReturnReason(String cancellationReasonCode) {
        if (cancellationReasonCode == null || cancellationReasonCode.isEmpty()) {
            return ReturnReasonCode.NARR;
        }
        
        // Map common cancellation reason codes to return reason codes
        switch (cancellationReasonCode) {
            case "CUST":
                return ReturnReasonCode.NARR; // Customer request - use narrative
            case "DUPL":
                return ReturnReasonCode.AM05; // Duplicate payment
            case "FRAD":
                return ReturnReasonCode.RR01; // Fraud - regulatory reason
            default:
                return ReturnReasonCode.NARR; // Default to narrative
        }
    }
    
    /**
     * Handle case where payment is not found.
     */
    private void handlePaymentNotFound(String endToEndId, String originalCamt056MsgId,
                                      String originalInstrId, String reasonCode, String additionalInfo,
                                      String ingressChannel, String instructingAgentBic) {
        // Create case anyway for tracking
        CancellationCase cancellationCase = caseManagementService.createCase(
            endToEndId, originalCamt056MsgId, originalInstrId, null,
            reasonCode, additionalInfo, ingressChannel, instructingAgentBic);
        
        // Send refusal via camt.029
        // Note: We need a minimal PaymentEvent for camt.029 generation
        PaymentEvent dummyEvent = PaymentEvent.builder()
            .endToEndId(endToEndId)
            .msgId(originalInstrId)
            .build();
        
        String camt029Xml = camt029Generator.generateCamt029(
            dummyEvent, originalCamt056MsgId, cancellationCase.getCaseId(),
            Camt029Generator.ResolutionStatus.RJCT, reasonCode, 
            "Payment not found - cannot process refund");
        
        notificationService.publishCamt029(camt029Xml, endToEndId);
        
        String resolutionMsgId = extractMessageId(camt029Xml);
        caseManagementService.setCaseRefused(cancellationCase, resolutionMsgId);
    }
    
    /**
     * Parse camt.056 XML to extract cancellation details.
     */
    private CancellationRequest parseCamt056(String camt056Xml) {
        try {
            // Simple XML parsing - in production, use proper JAXB or similar
            String originalInstrId = extractXmlValue(camt056Xml, "OrgnlInstrId");
            String originalEndToEndId = extractXmlValue(camt056Xml, "OrgnlEndToEndId");
            String originalTxId = extractXmlValue(camt056Xml, "OrgnlTxId");
            String reasonCode = extractXmlValue(camt056Xml, "Cd");
            String additionalInfo = extractXmlValue(camt056Xml, "AddtlInf");
            
            return new CancellationRequest(originalInstrId, originalEndToEndId, 
                originalTxId, reasonCode, additionalInfo);
            
        } catch (Exception e) {
            log.error("Error parsing camt.056 XML", e);
            throw new RuntimeException("Failed to parse camt.056 XML", e);
        }
    }
    
    /**
     * Extract camt.056 message ID.
     */
    private String extractCamt056MsgId(String camt056Xml) {
        String msgId = extractXmlValue(camt056Xml, "MsgId");
        return msgId != null ? msgId : "CXL-" + System.currentTimeMillis();
    }
    
    /**
     * Extract ingress channel from message (if available in headers/metadata).
     */
    private String extractIngressChannel(String camt056Xml) {
        // In production, this would come from Kafka headers or message metadata
        // For now, try to detect from message structure
        if (camt056Xml.contains("SWIFT") || camt056Xml.contains("swift")) {
            return "SWIFT";
        } else if (camt056Xml.contains("FED") || camt056Xml.contains("fed")) {
            return "FED";
        } else if (camt056Xml.contains("CHIPS") || camt056Xml.contains("chips")) {
            return "CHIPS";
        } else {
            return "WELLS";
        }
    }
    
    /**
     * Extract instructing agent BIC from camt.056.
     */
    private String extractInstructingAgentBic(String camt056Xml) {
        // Look for InstgAgt/FinInstnId/BICFI
        String bic = extractXmlValue(camt056Xml, "BICFI");
        if (bic == null) {
            bic = extractXmlValue(camt056Xml, "BIC");
        }
        return bic != null ? bic : "UNKNOWN";
    }
    
    /**
     * Extract message ID from XML.
     */
    private String extractMessageId(String xml) {
        String msgId = extractXmlValue(xml, "MsgId");
        return msgId != null ? msgId : "MSG-" + System.currentTimeMillis();
    }
    
    /**
     * Extract XML element value (simple parsing).
     */
    private String extractXmlValue(String xml, String elementName) {
        // Try with namespace
        String[] patterns = {
            "<" + elementName + ">",
            "<" + elementName + " ",
            ":" + elementName + ">",
            ":" + elementName + " "
        };
        
        for (String pattern : patterns) {
            int startIndex = xml.indexOf(pattern);
            if (startIndex != -1) {
                startIndex = xml.indexOf(">", startIndex) + 1;
                int endIndex = xml.indexOf("</", startIndex);
                if (endIndex == -1) {
                    endIndex = xml.indexOf("/>", startIndex);
                }
                if (endIndex != -1) {
                    return xml.substring(startIndex, endIndex).trim();
                }
            }
        }
        
        return null;
    }
    
    
    /**
     * Cancellation request data structure.
     */
    private static class CancellationRequest {
        private final String originalInstrId;
        private final String originalEndToEndId;
        private final String originalTxId;
        private final String reasonCode;
        private final String additionalInfo;
        
        public CancellationRequest(String originalInstrId, String originalEndToEndId,
                                 String originalTxId, String reasonCode, String additionalInfo) {
            this.originalInstrId = originalInstrId;
            this.originalEndToEndId = originalEndToEndId;
            this.originalTxId = originalTxId;
            this.reasonCode = reasonCode;
            this.additionalInfo = additionalInfo;
        }
        
        public String getOriginalInstrId() { return originalInstrId; }
        public String getOriginalEndToEndId() { return originalEndToEndId; }
        // getOriginalTxId, getReasonCode, getAdditionalInfo are kept for future use
        @SuppressWarnings("unused")
        public String getOriginalTxId() { return originalTxId; }
        @SuppressWarnings("unused")
        public String getReasonCode() { return reasonCode; }
        @SuppressWarnings("unused")
        public String getAdditionalInfo() { return additionalInfo; }
    }
}

