package com.wellsfargo.payment.orchestrator.error;

import com.wellsfargo.payment.canonical.PaymentEvent;
import com.wellsfargo.payment.canonical.ServiceResult;
import com.wellsfargo.payment.canonical.enums.ServiceResultStatus;
import com.wellsfargo.payment.error.ErrorActionRequest;
import com.wellsfargo.payment.error.ErrorRecord;
import com.wellsfargo.payment.kafka.PaymentEventDeserializer;
import com.wellsfargo.payment.kafka.PaymentEventSerializer;
import com.wellsfargo.payment.kafka.ServiceResultDeserializer;
import com.wellsfargo.payment.notification.NotificationService;
import com.wellsfargo.payment.canonical.enums.Pacs002Status;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Error Management Service.
 * 
 * Consumes errors from all error topics and provides management capabilities:
 * - View all errors
 * - Fix and resume from failed step
 * - Restart from beginning
 * - Cancel and return payment
 */
@Service
public class ErrorManagementService {
    
    private static final Logger log = LoggerFactory.getLogger(ErrorManagementService.class);
    
    // Error topics
    private static final String TOPIC_ACCOUNT_VALIDATION_ERR = "service.errors.account_validation";
    private static final String TOPIC_ROUTING_VALIDATION_ERR = "service.errors.routing_validation";
    private static final String TOPIC_SANCTIONS_CHECK_ERR = "service.errors.sanctions_check";
    private static final String TOPIC_BALANCE_CHECK_ERR = "service.errors.balance_check";
    private static final String TOPIC_PAYMENT_POSTING_ERR = "service.errors.payment_posting";
    
    // Step topics for resuming
    private static final String TOPIC_ACCOUNT_VALIDATION_IN = "payments.step.account_validation";
    private static final String TOPIC_BALANCE_CHECK_IN = "payments.step.balance_check";
    private static final String TOPIC_PAYMENT_POSTING_IN = "payments.step.payment_posting";
    
    @Value("${kafka.bootstrap.servers:localhost:9092}")
    private String bootstrapServers;
    
    @Value("${kafka.consumer.group-id:error-management-group}")
    private String groupId;
    
    // Error storage (in-memory, in production use database)
    private final Map<String, ErrorRecord> errorStore = new ConcurrentHashMap<>();
    
    // Kafka consumers and producers
    private KafkaConsumer<String, ServiceResult> errorConsumer;
    private KafkaProducer<String, PaymentEvent> eventProducer;
    private NotificationService notificationService;
    
    // Thread management
    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    @PostConstruct
    public void init() {
        log.info("Initializing Error Management Service");
        
        // Create error consumer
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ServiceResultDeserializer.class);
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumerProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        
        errorConsumer = new KafkaConsumer<>(consumerProps);
        errorConsumer.subscribe(Arrays.asList(
            TOPIC_ACCOUNT_VALIDATION_ERR,
            TOPIC_ROUTING_VALIDATION_ERR,
            TOPIC_SANCTIONS_CHECK_ERR,
            TOPIC_BALANCE_CHECK_ERR,
            TOPIC_PAYMENT_POSTING_ERR
        ));
        
        // Create event producer
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, PaymentEventSerializer.class);
        producerProps.put(ProducerConfig.ACKS_CONFIG, "all");
        producerProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        producerProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        
        eventProducer = new KafkaProducer<>(producerProps);
        
        // Initialize notification service
        notificationService = new NotificationService(bootstrapServers);
        
        // Start error processing thread
        running.set(true);
        executorService = Executors.newSingleThreadExecutor();
        executorService.submit(this::processErrors);
        
        log.info("Error Management Service initialized and started");
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down Error Management Service");
        
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
        
        if (errorConsumer != null) {
            errorConsumer.close();
        }
        
        if (eventProducer != null) {
            eventProducer.close();
        }
        
        if (notificationService != null) {
            notificationService.shutdown();
        }
        
        log.info("Error Management Service shut down");
    }
    
    /**
     * Process errors from error topics.
     */
    private void processErrors() {
        log.info("Starting error processing thread");
        
        while (running.get()) {
            try {
                ConsumerRecords<String, ServiceResult> records = errorConsumer.poll(Duration.ofSeconds(1));
                
                for (ConsumerRecord<String, ServiceResult> record : records) {
                    try {
                        ServiceResult result = record.value();
                        if (result != null && 
                            (result.getStatus() == ServiceResultStatus.FAIL || 
                             result.getStatus() == ServiceResultStatus.ERROR)) {
                            
                            String endToEndId = result.getEndToEndId();
                            String serviceName = extractServiceName(record.topic());
                            
                            // Create error record
                            ErrorRecord errorRecord = ErrorRecord.builder()
                                .errorId(UUID.randomUUID().toString())
                                .endToEndId(endToEndId)
                                .serviceResult(result)
                                .serviceName(serviceName)
                                .errorTopic(record.topic())
                                .errorTimestamp(Instant.now().toString())
                                .lastSuccessfulStep(determineLastSuccessfulStep(serviceName))
                                .severity(determineSeverity(result))
                                .category(determineCategory(result))
                                .status(ErrorRecord.ErrorStatus.NEW)
                                .createdAt(Instant.now().toString())
                                .updatedAt(Instant.now().toString())
                                .build();
                            
                            // Store error
                            errorStore.put(errorRecord.getErrorId(), errorRecord);
                            
                            log.info("Error recorded - errorId={}, E2E={}, service={}", 
                                errorRecord.getErrorId(), endToEndId, serviceName);
                            
                            errorConsumer.commitSync();
                        }
                    } catch (Exception e) {
                        log.error("Error processing error record: {}", record.key(), e);
                    }
                }
            } catch (Exception e) {
                log.error("Error in error processing loop", e);
            }
        }
        
        log.info("Error processing thread stopped");
    }
    
    /**
     * Get all errors.
     */
    public List<ErrorRecord> getAllErrors() {
        return new ArrayList<>(errorStore.values());
    }
    
    /**
     * Get error by ID.
     */
    public ErrorRecord getError(String errorId) {
        return errorStore.get(errorId);
    }
    
    /**
     * Get errors by end-to-end ID.
     */
    public List<ErrorRecord> getErrorsByEndToEndId(String endToEndId) {
        return errorStore.values().stream()
            .filter(e -> e.getEndToEndId().equals(endToEndId))
            .collect(Collectors.toList());
    }
    
    /**
     * Get errors by service name.
     */
    public List<ErrorRecord> getErrorsByService(String serviceName) {
        return errorStore.values().stream()
            .filter(e -> e.getServiceName().equals(serviceName))
            .collect(Collectors.toList());
    }
    
    /**
     * Get errors by status.
     */
    public List<ErrorRecord> getErrorsByStatus(ErrorRecord.ErrorStatus status) {
        return errorStore.values().stream()
            .filter(e -> e.getStatus() == status)
            .collect(Collectors.toList());
    }
    
    /**
     * Fix and resume from failed step.
     */
    public void fixAndResume(ErrorActionRequest request, PaymentEvent paymentEvent) {
        ErrorRecord error = errorStore.get(request.getErrorId());
        if (error == null) {
            throw new IllegalArgumentException("Error not found: " + request.getErrorId());
        }
        
        log.info("Fixing and resuming error - errorId={}, E2E={}", 
            request.getErrorId(), error.getEndToEndId());
        
        // Update error status
        error.setStatus(ErrorRecord.ErrorStatus.IN_PROGRESS);
        error.setUpdatedAt(Instant.now().toString());
        error.setAssignedTo(request.getPerformedBy());
        
        // Determine which step to resume from
        String resumeTopic = getResumeTopic(error.getServiceName());
        
        // Publish fixed payment event to resume topic
        ProducerRecord<String, PaymentEvent> record = new ProducerRecord<>(
            resumeTopic, error.getEndToEndId(), paymentEvent);
        
        eventProducer.send(record, (metadata, exception) -> {
            if (exception != null) {
                log.error("Failed to resume payment - errorId={}, E2E={}", 
                    request.getErrorId(), error.getEndToEndId(), exception);
                error.setStatus(ErrorRecord.ErrorStatus.NEW);
            } else {
                log.info("Payment resumed - errorId={}, E2E={}, topic={}", 
                    request.getErrorId(), error.getEndToEndId(), resumeTopic);
                error.setStatus(ErrorRecord.ErrorStatus.FIXED);
            }
            error.setUpdatedAt(Instant.now().toString());
        });
    }
    
    /**
     * Restart from beginning (account validation).
     */
    public void restartFromBeginning(ErrorActionRequest request, PaymentEvent paymentEvent) {
        ErrorRecord error = errorStore.get(request.getErrorId());
        if (error == null) {
            throw new IllegalArgumentException("Error not found: " + request.getErrorId());
        }
        
        log.info("Restarting payment from beginning - errorId={}, E2E={}", 
            request.getErrorId(), error.getEndToEndId());
        
        // Update error status
        error.setStatus(ErrorRecord.ErrorStatus.IN_PROGRESS);
        error.setUpdatedAt(Instant.now().toString());
        error.setAssignedTo(request.getPerformedBy());
        
        // Publish to account validation (first step)
        ProducerRecord<String, PaymentEvent> record = new ProducerRecord<>(
            TOPIC_ACCOUNT_VALIDATION_IN, error.getEndToEndId(), paymentEvent);
        
        eventProducer.send(record, (metadata, exception) -> {
            if (exception != null) {
                log.error("Failed to restart payment - errorId={}, E2E={}", 
                    request.getErrorId(), error.getEndToEndId(), exception);
                error.setStatus(ErrorRecord.ErrorStatus.NEW);
            } else {
                log.info("Payment restarted - errorId={}, E2E={}", 
                    request.getErrorId(), error.getEndToEndId());
                error.setStatus(ErrorRecord.ErrorStatus.FIXED);
            }
            error.setUpdatedAt(Instant.now().toString());
        });
    }
    
    /**
     * Cancel and return payment.
     */
    public void cancelAndReturn(ErrorActionRequest request, PaymentEvent paymentEvent) {
        ErrorRecord error = errorStore.get(request.getErrorId());
        if (error == null) {
            throw new IllegalArgumentException("Error not found: " + request.getErrorId());
        }
        
        log.info("Cancelling and returning payment - errorId={}, E2E={}", 
            request.getErrorId(), error.getEndToEndId());
        
        // Update error status
        error.setStatus(ErrorRecord.ErrorStatus.CANCELLED);
        error.setUpdatedAt(Instant.now().toString());
        error.setAssignedTo(request.getPerformedBy());
        
        // Send CANC status via PACS.002
        if (notificationService != null) {
            notificationService.publishStatus(paymentEvent, Pacs002Status.CANC, 
                "CANC", "Payment cancelled: " + request.getCancellationReason());
        }
        
        log.info("Payment cancelled - errorId={}, E2E={}", 
            request.getErrorId(), error.getEndToEndId());
    }
    
    /**
     * Extract service name from topic.
     */
    private String extractServiceName(String topic) {
        if (topic.contains("account_validation")) return "account_validation";
        if (topic.contains("routing_validation")) return "routing_validation";
        if (topic.contains("sanctions_check")) return "sanctions_check";
        if (topic.contains("balance_check")) return "balance_check";
        if (topic.contains("payment_posting")) return "payment_posting";
        return "unknown";
    }
    
    /**
     * Determine last successful step.
     */
    private String determineLastSuccessfulStep(String failedService) {
        switch (failedService) {
            case "account_validation": return "ingress";
            case "routing_validation": return "account_validation";
            case "sanctions_check": return "routing_validation";
            case "balance_check": return "sanctions_check";
            case "payment_posting": return "balance_check";
            default: return "unknown";
        }
    }
    
    /**
     * Determine error severity.
     */
    private ErrorRecord.ErrorSeverity determineSeverity(ServiceResult result) {
        String message = result.getErrorMessage() != null ? result.getErrorMessage().toLowerCase() : "";
        if (message.contains("critical") || message.contains("sanctions")) {
            return ErrorRecord.ErrorSeverity.CRITICAL;
        } else if (message.contains("balance") || message.contains("account")) {
            return ErrorRecord.ErrorSeverity.HIGH;
        } else if (message.contains("validation")) {
            return ErrorRecord.ErrorSeverity.MEDIUM;
        }
        return ErrorRecord.ErrorSeverity.LOW;
    }
    
    /**
     * Determine error category.
     */
    private ErrorRecord.ErrorCategory determineCategory(ServiceResult result) {
        String message = result.getErrorMessage() != null ? result.getErrorMessage().toLowerCase() : "";
        if (message.contains("sanctions") || message.contains("balance") || message.contains("account")) {
            return ErrorRecord.ErrorCategory.BUSINESS;
        } else if (message.contains("timeout") || message.contains("connection")) {
            return ErrorRecord.ErrorCategory.OPERATIONAL;
        }
        return ErrorRecord.ErrorCategory.TECHNICAL;
    }
    
    /**
     * Get resume topic based on failed service.
     */
    private String getResumeTopic(String serviceName) {
        switch (serviceName) {
            case "account_validation": return TOPIC_ACCOUNT_VALIDATION_IN;
            case "balance_check": return TOPIC_BALANCE_CHECK_IN;
            case "payment_posting": return TOPIC_PAYMENT_POSTING_IN;
            default: return TOPIC_ACCOUNT_VALIDATION_IN; // Default to beginning
        }
    }
}

