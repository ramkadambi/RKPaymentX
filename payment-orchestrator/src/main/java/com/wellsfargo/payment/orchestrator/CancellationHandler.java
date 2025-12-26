package com.wellsfargo.payment.orchestrator;

import com.wellsfargo.payment.canonical.PaymentEvent;
import com.wellsfargo.payment.canonical.enums.Pacs002Status;
import com.wellsfargo.payment.notification.NotificationService;
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
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cancellation Handler for processing camt.056 cancellation requests.
 * 
 * This service:
 * - Consumes camt.056 cancellation requests from payments.cancellation.in
 * - Validates that the payment can be cancelled (not yet processed)
 * - Cancels the payment and sends CANC status via PACS.002
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
    
    private KafkaConsumer<String, String> consumer;
    private NotificationService notificationService;
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
        log.info("Initializing Cancellation Handler");
        
        // Initialize notification service
        notificationService = new NotificationService(bootstrapServers);
        
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
     * Handle camt.056 cancellation request.
     */
    private void handleCancellationRequest(String camt056Xml) {
        try {
            // Parse camt.056 XML to extract cancellation details
            CancellationRequest request = parseCamt056(camt056Xml);
            
            String endToEndId = request.getOriginalEndToEndId();
            String originalInstrId = request.getOriginalInstrId();
            
            log.info("Processing cancellation request - E2E={}, InstrId={}", 
                endToEndId, originalInstrId);
            
            // Check if payment exists and can be cancelled
            PaymentEvent event = null;
            if (orchestratorService != null) {
                event = orchestratorService.getPaymentEvent(endToEndId);
            }
            
            if (event == null) {
                log.warn("Payment not found for cancellation - E2E={}", endToEndId);
                // Could still send CANC status if instructed bank requests it
                return;
            }
            
            // Check if payment can be cancelled (not yet settled)
            String processingState = event.getProcessingState();
            if (processingState != null && 
                (processingState.equals("COMPLETED") || processingState.equals("SETTLED"))) {
                log.warn("Payment cannot be cancelled - already processed - E2E={}, state={}", 
                    endToEndId, processingState);
                // Send RJCT status instead
                notificationService.publishStatus(event, Pacs002Status.RJCT, 
                    "CANC", "Payment cannot be cancelled - already processed");
                return;
            }
            
            // Cancel the payment
            log.info("Cancelling payment - E2E={}", endToEndId);
            
            // Remove from orchestrator cache to prevent further processing
            if (orchestratorService != null) {
                orchestratorService.removePaymentEvent(endToEndId);
            }
            
            // Send CANC status via PACS.002
            notificationService.publishStatus(event, Pacs002Status.CANC, 
                "CANC", "Payment cancelled as requested");
            
            log.info("Payment cancelled successfully - E2E={}", endToEndId);
            
        } catch (Exception e) {
            log.error("Error handling cancellation request", e);
        }
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
     * Extract XML element value (simple parsing).
     */
    private String extractXmlValue(String xml, String elementName) {
        String startTag = "<" + elementName + ">";
        String endTag = "</" + elementName + ">";
        
        int startIndex = xml.indexOf(startTag);
        if (startIndex == -1) {
            return null;
        }
        
        startIndex += startTag.length();
        int endIndex = xml.indexOf(endTag, startIndex);
        if (endIndex == -1) {
            return null;
        }
        
        return xml.substring(startIndex, endIndex).trim();
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

