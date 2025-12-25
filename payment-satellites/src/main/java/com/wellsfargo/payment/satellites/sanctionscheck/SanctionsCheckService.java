package com.wellsfargo.payment.satellites.sanctionscheck;

import com.wellsfargo.payment.canonical.PaymentEvent;
import com.wellsfargo.payment.canonical.ServiceResult;
import com.wellsfargo.payment.canonical.enums.ServiceResultStatus;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sanctions Check Service for payment processing.
 * 
 * This service:
 * 1. Consumes PaymentEvent from payments.step.sanctions_check
 * 2. Checks creditor account against sanctions list
 * 3. Checks creditor party name against sanctions list
 * 4. Checks creditor agent (BIC) against sanctions list
 * 5. Publishes ServiceResult to service.results.sanctions_check
 * 6. Publishes errors to service.errors.sanctions_check
 * 
 * Constraints:
 * - Uses mock sanctions list (in-memory)
 * - No external sanctions API calls
 * - Flags specific credit accounts for testing
 */
@Service
public class SanctionsCheckService {
    
    private static final Logger log = LoggerFactory.getLogger(SanctionsCheckService.class);
    
    // Topic names
    private static final String TOPIC_INPUT = "payments.step.sanctions_check";
    private static final String TOPIC_RESULT = "service.results.sanctions_check";
    private static final String TOPIC_ERROR = "service.errors.sanctions_check";
    
    private static final String SERVICE_NAME = "sanctions_check";
    
    // Mock sanctions list - accounts/parties that will fail sanctions check
    // In production, this would be loaded from external sanctions database/API
    private static final Set<String> SANCTIONED_ACCOUNTS = new HashSet<>(Arrays.asList(
        "9876543210",      // Credit account from test data
        "1234567890",      // Another credit account
        "SANCTIONS-001",   // Explicitly flagged account
        "SANCTIONS-002"    // Another flagged account
    ));
    
    private static final Set<String> SANCTIONED_PARTY_NAMES = new HashSet<>(Arrays.asList(
        "SANCTIONS TARGET",           // Party name that will fail
        "BLOCKED ENTITY",             // Another blocked party
        "PROHIBITED CUSTOMER"         // Another prohibited party
    ));
    
    private static final Set<String> SANCTIONED_BICS = new HashSet<>(Arrays.asList(
        "SANCTXXX",       // Sanctioned BIC
        "BLOCKEDXXX"      // Blocked BIC
    ));
    
    @Value("${kafka.bootstrap.servers:localhost:9092}")
    private String bootstrapServers;
    
    @Value("${kafka.consumer.group-id:sanctions-check-group}")
    private String groupId;
    
    @Value("${kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;
    
    // Kafka consumers and producers
    private KafkaConsumer<String, PaymentEvent> consumer;
    private KafkaProducer<String, ServiceResult> resultProducer;
    
    // Thread management
    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    @PostConstruct
    public void init() {
        log.info("Initializing Sanctions Check Service");
        
        // Create consumer
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, 
            com.wellsfargo.payment.kafka.PaymentEventDeserializer.class);
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumerProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        
        consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Collections.singletonList(TOPIC_INPUT));
        
        // Create result producer
        Properties resultProducerProps = new Properties();
        resultProducerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        resultProducerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        resultProducerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, 
            com.wellsfargo.payment.kafka.ServiceResultSerializer.class);
        resultProducerProps.put(ProducerConfig.ACKS_CONFIG, "1");
        resultProducerProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        resultProducerProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        resultProducerProps.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        resultProducerProps.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        
        resultProducer = new KafkaProducer<>(resultProducerProps);
        
        // Start processing thread
        running.set(true);
        executorService = Executors.newSingleThreadExecutor();
        executorService.submit(this::processEvents);
        
        log.info("Sanctions Check Service initialized and started. Sanctioned accounts: {}, parties: {}, BICs: {}", 
            SANCTIONED_ACCOUNTS.size(), SANCTIONED_PARTY_NAMES.size(), SANCTIONED_BICS.size());
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down Sanctions Check Service");
        
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
        
        if (resultProducer != null) {
            resultProducer.close();
        }
        
        log.info("Sanctions Check Service shut down");
    }
    
    /**
     * Process PaymentEvents from Kafka.
     */
    private void processEvents() {
        log.info("Starting sanctions check event processing thread");
        
        while (running.get()) {
            try {
                ConsumerRecords<String, PaymentEvent> records = consumer.poll(Duration.ofSeconds(1));
                
                for (ConsumerRecord<String, PaymentEvent> record : records) {
                    try {
                        PaymentEvent event = record.value();
                        if (event != null) {
                            handleEvent(event);
                            consumer.commitSync();
                        }
                    } catch (Exception e) {
                        log.error("Error processing event: {}", record.key(), e);
                        // Continue processing other records
                    }
                }
            } catch (Exception e) {
                log.error("Error in event processing loop", e);
            }
        }
        
        log.info("Sanctions check event processing thread stopped");
    }
    
    /**
     * Handle a PaymentEvent: perform sanctions check.
     */
    private void handleEvent(PaymentEvent event) {
        String endToEndId = event.getEndToEndId();
        String msgId = event.getMsgId();
        
        log.info("Received PaymentEvent for sanctions check: E2E={}, MsgId={}, Amount={} {}", 
            endToEndId, msgId, event.getAmount(), event.getCurrency());
        
        // Perform sanctions check
        SanctionsCheckResult checkResult = performSanctionsCheck(event);
        
        // Create ServiceResult
        ServiceResult result = ServiceResult.builder()
            .endToEndId(endToEndId)
            .serviceName(SERVICE_NAME)
            .status(checkResult.getStatus())
            .errorMessage(checkResult.getErrorMessage())
            .processingTimestamp(Instant.now().toString())
            .build();
        
        // Publish ServiceResult
        publishServiceResult(result);
        
        // Publish error if FAIL/ERROR
        if (checkResult.getStatus() == ServiceResultStatus.FAIL || 
            checkResult.getStatus() == ServiceResultStatus.ERROR) {
            publishError(result);
        }
        
        log.info("Completed sanctions check for E2E={}, status={}, reason={}", 
            endToEndId, checkResult.getStatus(), checkResult.getErrorMessage());
    }
    
    /**
     * Perform sanctions check on PaymentEvent.
     * Checks creditor account, party name, and agent BIC.
     */
    private SanctionsCheckResult performSanctionsCheck(PaymentEvent event) {
        List<String> violations = new ArrayList<>();
        
        // Check creditor account ID
        if (event.getCreditor() != null && event.getCreditor().getAccountId() != null) {
            String creditorAccountId = event.getCreditor().getAccountId();
            if (SANCTIONED_ACCOUNTS.contains(creditorAccountId)) {
                violations.add(String.format("Creditor account is sanctioned: %s", creditorAccountId));
                log.warn("Sanctions violation: Creditor account {} is on sanctions list", creditorAccountId);
            }
        }
        
        // Check creditor party name
        if (event.getCreditor() != null && event.getCreditor().getName() != null) {
            String creditorName = event.getCreditor().getName().toUpperCase();
            for (String sanctionedName : SANCTIONED_PARTY_NAMES) {
                if (creditorName.contains(sanctionedName.toUpperCase())) {
                    violations.add(String.format("Creditor party name matches sanctions list: %s", 
                        event.getCreditor().getName()));
                    log.warn("Sanctions violation: Creditor party name '{}' matches sanctions pattern", 
                        event.getCreditor().getName());
                    break;
                }
            }
        }
        
        // Check creditor agent BIC
        if (event.getCreditorAgent() != null && event.getCreditorAgent().getIdValue() != null) {
            String creditorBic = event.getCreditorAgent().getIdValue();
            if (SANCTIONED_BICS.contains(creditorBic)) {
                violations.add(String.format("Creditor agent BIC is sanctioned: %s", creditorBic));
                log.warn("Sanctions violation: Creditor agent BIC {} is on sanctions list", creditorBic);
            }
        }
        
        // If violations found, return FAIL
        if (!violations.isEmpty()) {
            String errorMessage = String.join("; ", violations);
            return SanctionsCheckResult.builder()
                .status(ServiceResultStatus.FAIL)
                .errorMessage(errorMessage)
                .violations(violations)
                .build();
        }
        
        // No violations found - PASS
        return SanctionsCheckResult.builder()
            .status(ServiceResultStatus.PASS)
            .build();
    }
    
    /**
     * Publish ServiceResult to Kafka result topic.
     */
    private void publishServiceResult(ServiceResult result) {
        String endToEndId = result.getEndToEndId();
        
        log.debug("Publishing ServiceResult to topic={}, E2E={}, status={}", 
            TOPIC_RESULT, endToEndId, result.getStatus());
        
        try {
            ProducerRecord<String, ServiceResult> record = new ProducerRecord<>(
                TOPIC_RESULT, endToEndId, result);
            resultProducer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Failed to publish ServiceResult to topic={}, E2E={}", 
                        TOPIC_RESULT, endToEndId, exception);
                } else {
                    log.debug("Published ServiceResult to topic={}, partition={}, offset={}, E2E={}", 
                        metadata.topic(), metadata.partition(), metadata.offset(), endToEndId);
                }
            });
        } catch (Exception e) {
            log.error("Error publishing ServiceResult to topic={}, E2E={}", TOPIC_RESULT, endToEndId, e);
        }
    }
    
    /**
     * Publish error ServiceResult to Kafka error topic.
     */
    private void publishError(ServiceResult result) {
        String endToEndId = result.getEndToEndId();
        
        log.warn("Publishing error to topic={}, E2E={}, status={}", 
            TOPIC_ERROR, endToEndId, result.getStatus());
        
        try {
            ProducerRecord<String, ServiceResult> record = new ProducerRecord<>(
                TOPIC_ERROR, endToEndId, result);
            resultProducer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Failed to publish error to topic={}, E2E={}", TOPIC_ERROR, endToEndId, exception);
                } else {
                    log.debug("Published error to topic={}, partition={}, offset={}, E2E={}", 
                        metadata.topic(), metadata.partition(), metadata.offset(), endToEndId);
                }
            });
        } catch (Exception e) {
            log.error("Error publishing error to topic={}, E2E={}", TOPIC_ERROR, endToEndId, e);
        }
    }
    
    /**
     * Helper class to hold sanctions check result.
     */
    private static class SanctionsCheckResult {
        private final ServiceResultStatus status;
        private final String errorMessage;
        private final List<String> violations;
        
        private SanctionsCheckResult(Builder builder) {
            this.status = builder.status;
            this.errorMessage = builder.errorMessage;
            this.violations = builder.violations != null ? new ArrayList<>(builder.violations) : new ArrayList<>();
        }
        
        public ServiceResultStatus getStatus() {
            return status;
        }
        
        public String getErrorMessage() {
            return errorMessage;
        }
        
        public List<String> getViolations() {
            return violations;
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private ServiceResultStatus status;
            private String errorMessage;
            private List<String> violations;
            
            public Builder status(ServiceResultStatus status) {
                this.status = status;
                return this;
            }
            
            public Builder errorMessage(String errorMessage) {
                this.errorMessage = errorMessage;
                return this;
            }
            
            public Builder violations(List<String> violations) {
                this.violations = violations;
                return this;
            }
            
            public SanctionsCheckResult build() {
                return new SanctionsCheckResult(this);
            }
        }
    }
}

