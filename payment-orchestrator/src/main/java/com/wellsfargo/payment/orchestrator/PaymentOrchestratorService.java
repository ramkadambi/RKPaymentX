package com.wellsfargo.payment.orchestrator;

import com.wellsfargo.payment.canonical.PaymentEvent;
import com.wellsfargo.payment.canonical.ServiceResult;
import com.wellsfargo.payment.canonical.enums.PaymentStatus;
import com.wellsfargo.payment.canonical.enums.Pacs002Status;
import com.wellsfargo.payment.canonical.enums.ServiceResultStatus;
import com.wellsfargo.payment.notification.NotificationService;
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
import org.springframework.beans.factory.annotation.Autowired;
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

/**
 * Payment Orchestrator Service.
 * 
 * Orchestrates sequential payment processing through satellites:
 * 1. Consumes PaymentEvent from payments.orchestrator.in
 * 2. Publishes to payments.step.account_validation
 * 3. Consumes ServiceResult topics
 * 4. Routes PaymentEvent to next step based on service results
 * 5. Publishes final status to payments.final.status
 * 
 * Flow:
 * - account_validation → routing_validation (enriched PaymentEvent published directly by account_validation)
 * - routing_validation → sanctions_check (routed PaymentEvent published directly by routing_validation)
 * - sanctions_check → balance_check
 * - balance_check → payment_posting
 * - payment_posting → final status
 * 
 * Constraints:
 * - Kafka only (no DB access)
 * - No rule evaluation
 * - Idempotent handling per end_to_end_id
 * - Deterministic execution
 */
@Service
public class PaymentOrchestratorService {
    
    private static final Logger log = LoggerFactory.getLogger(PaymentOrchestratorService.class);
    
    // Topic names
    private static final String TOPIC_ORCHESTRATOR_IN = "payments.orchestrator.in";
    private static final String TOPIC_ACCOUNT_VALIDATION_IN = "payments.step.account_validation";
    private static final String TOPIC_BALANCE_CHECK_IN = "payments.step.balance_check";
    private static final String TOPIC_PAYMENT_POSTING_IN = "payments.step.payment_posting";
    private static final String TOPIC_FINAL_STATUS = "payments.final.status";
    
    // Service result topics
    private static final String TOPIC_ACCOUNT_VALIDATION_OUT = "service.results.account_validation";
    private static final String TOPIC_ROUTING_VALIDATION_OUT = "service.results.routing_validation";
    private static final String TOPIC_SANCTIONS_CHECK_OUT = "service.results.sanctions_check";
    private static final String TOPIC_BALANCE_CHECK_OUT = "service.results.balance_check";
    private static final String TOPIC_PAYMENT_POSTING_OUT = "service.results.payment_posting";
    
    // Error topics (for reference, not consumed)
    private static final String TOPIC_ACCOUNT_VALIDATION_ERR = "service.errors.account_validation";
    private static final String TOPIC_ROUTING_VALIDATION_ERR = "service.errors.routing_validation";
    private static final String TOPIC_SANCTIONS_CHECK_ERR = "service.errors.sanctions_check";
    private static final String TOPIC_BALANCE_CHECK_ERR = "service.errors.balance_check";
    private static final String TOPIC_PAYMENT_POSTING_ERR = "service.errors.payment_posting";
    
    @Value("${kafka.bootstrap.servers:localhost:9092}")
    private String bootstrapServers;
    
    @Value("${kafka.consumer.group-id:payment-orchestrator-group}")
    private String groupId;
    
    @Value("${kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;
    
    @Value("${error.management.mock.mode:false}")
    private boolean mockMode;
    
    // Kafka consumers and producers
    private KafkaConsumer<String, PaymentEvent> ingressConsumer;
    private KafkaConsumer<String, ServiceResult> resultsConsumer;
    private KafkaProducer<String, PaymentEvent> eventProducer;
    
    // State tracking
    private final Map<String, PaymentEvent> eventCache = new ConcurrentHashMap<>();
    private final Set<String> finalStatusEmitted = ConcurrentHashMap.newKeySet();
    
    /**
     * Get payment event by endToEndId (for cancellation handler).
     */
    public PaymentEvent getPaymentEvent(String endToEndId) {
        return eventCache.get(endToEndId);
    }
    
    /**
     * Remove payment event (for cancellation handler).
     */
    public void removePaymentEvent(String endToEndId) {
        eventCache.remove(endToEndId);
    }
    
    // Thread management
    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    // Notification and cancellation services
    private NotificationService notificationService;
    
    @Autowired(required = false)
    private CancellationHandler cancellationHandler;
    
    @PostConstruct
    public void init() {
        log.info("Initializing Payment Orchestrator Service (mockMode={})", mockMode);
        
        if (mockMode) {
            log.info("Running in MOCK MODE - Kafka consumers/producers will not be initialized");
            // Initialize notification service for PACS.002/PACS.004 generation (but won't publish to Kafka)
            notificationService = new NotificationService(bootstrapServers);
            log.info("Payment Orchestrator Service initialized in MOCK MODE");
            return;
        }
        
        // Create ingress consumer
        Properties ingressProps = new Properties();
        ingressProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        ingressProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId + "-ingress");
        ingressProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        ingressProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        ingressProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, 
            com.wellsfargo.payment.kafka.PaymentEventDeserializer.class);
        ingressProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        ingressProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        
        ingressConsumer = new KafkaConsumer<>(ingressProps);
        ingressConsumer.subscribe(Collections.singletonList(TOPIC_ORCHESTRATOR_IN));
        
        // Create results consumer
        Properties resultsProps = new Properties();
        resultsProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        resultsProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId + "-results");
        resultsProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        resultsProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        resultsProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, 
            com.wellsfargo.payment.kafka.ServiceResultDeserializer.class);
        resultsProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        resultsProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        
        List<String> resultTopics = Arrays.asList(
            TOPIC_ACCOUNT_VALIDATION_OUT,
            TOPIC_ROUTING_VALIDATION_OUT,
            TOPIC_SANCTIONS_CHECK_OUT,
            TOPIC_BALANCE_CHECK_OUT,
            TOPIC_PAYMENT_POSTING_OUT
        );
        
        resultsConsumer = new KafkaConsumer<>(resultsProps);
        resultsConsumer.subscribe(resultTopics);
        
        // Create event producer
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, 
            com.wellsfargo.payment.kafka.PaymentEventSerializer.class);
        producerProps.put(ProducerConfig.ACKS_CONFIG, "all");
        producerProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        producerProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        producerProps.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        producerProps.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        
        eventProducer = new KafkaProducer<>(producerProps);
        
        // Initialize notification service
        notificationService = new NotificationService(bootstrapServers);
        
        // Start processing threads
        running.set(true);
        executorService = Executors.newFixedThreadPool(2);
        
        executorService.submit(this::processIngressEvents);
        executorService.submit(this::processServiceResults);
        
        log.info("Payment Orchestrator Service initialized and started");
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down Payment Orchestrator Service");
        
        running.set(false);
        
        if (mockMode) {
            if (notificationService != null) {
                notificationService.shutdown();
            }
            log.info("Payment Orchestrator Service shut down (mock mode)");
            return;
        }
        
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
        
        if (ingressConsumer != null) {
            ingressConsumer.close();
        }
        
        if (resultsConsumer != null) {
            resultsConsumer.close();
        }
        
        if (eventProducer != null) {
            eventProducer.close();
        }
        
        if (notificationService != null) {
            notificationService.shutdown();
        }
        
        log.info("Payment Orchestrator Service shut down");
    }
    
    /**
     * Process ingress PaymentEvents.
     */
    private void processIngressEvents() {
        if (mockMode) {
            log.info("Mock mode: Ingress event processing thread not started");
            return;
        }
        
        log.info("Starting ingress event processing thread");
        
        while (running.get()) {
            try {
                ConsumerRecords<String, PaymentEvent> records = ingressConsumer.poll(Duration.ofSeconds(1));
                
                for (ConsumerRecord<String, PaymentEvent> record : records) {
                    try {
                        PaymentEvent event = record.value();
                        if (event != null) {
                            handleIngressPayment(event);
                            ingressConsumer.commitSync();
                        }
                    } catch (Exception e) {
                        log.error("Error processing ingress event: {}", record.key(), e);
                        // Continue processing other records
                    }
                }
            } catch (Exception e) {
                log.error("Error in ingress event processing loop", e);
            }
        }
        
        log.info("Ingress event processing thread stopped");
    }
    
    /**
     * Process ServiceResults from satellites.
     */
    private void processServiceResults() {
        if (mockMode) {
            log.info("Mock mode: Service results processing thread not started");
            return;
        }
        
        log.info("Starting service result processing thread");
        
        while (running.get()) {
            try {
                ConsumerRecords<String, ServiceResult> records = resultsConsumer.poll(Duration.ofSeconds(1));
                
                for (ConsumerRecord<String, ServiceResult> record : records) {
                    try {
                        ServiceResult result = record.value();
                        if (result != null) {
                            handleServiceResult(result);
                            resultsConsumer.commitSync();
                        }
                    } catch (Exception e) {
                        log.error("Error processing service result: {}", record.key(), e);
                        // Continue processing other records
                    }
                }
            } catch (Exception e) {
                log.error("Error in service result processing loop", e);
            }
        }
        
        log.info("Service result processing thread stopped");
    }
    
    /**
     * Handle incoming PaymentEvent from ingress.
     */
    private void handleIngressPayment(PaymentEvent event) {
        String endToEndId = event.getEndToEndId();
        
        // Extract message type for structured logging
        String messageType = extractMessageType(event);
        
        // Idempotency check: if we already have this event, skip
        if (eventCache.containsKey(endToEndId)) {
            log.debug("PaymentEvent already cached - endToEndId={}, messageType={}, stage=ORCHESTRATOR_RECEIVE", 
                endToEndId, messageType);
            return;
        }
        
        // Structured logging: endToEndId, message type, current stage
        log.info("PaymentEvent received from ingress - endToEndId={}, messageType={}, stage=ORCHESTRATOR_RECEIVE", 
            endToEndId, messageType);
        
        // Cache the event
        eventCache.put(endToEndId, event);
        
        // Register with cancellation handler (set orchestrator reference if needed)
        if (cancellationHandler != null) {
            if (cancellationHandler != null) {
                cancellationHandler.setOrchestratorService(this);
            }
        }
        
        // Send RCVD status notification
        if (notificationService != null) {
            notificationService.publishStatus(event, Pacs002Status.RCVD, 
                "RCVD", "Payment received and acknowledged");
        }
        
        // Forward to account validation (first step)
        publishToStep(TOPIC_ACCOUNT_VALIDATION_IN, event);
    }
    
    /**
     * Extract message type string from PaymentEvent for logging.
     * Maps MessageSource enum to PACS_008 or PACS_009 string.
     */
    private String extractMessageType(PaymentEvent event) {
        if (event.getSourceMessageType() == null) {
            return "UNKNOWN";
        }
        
        String sourceType = event.getSourceMessageType().getValue();
        if ("ISO20022_PACS008".equals(sourceType)) {
            return "PACS_008";
        } else if ("ISO20022_PACS009".equals(sourceType)) {
            return "PACS_009";
        } else {
            return sourceType;
        }
    }
    
    /**
     * Handle ServiceResult from a satellite.
     */
    private void handleServiceResult(ServiceResult result) {
        String endToEndId = result.getEndToEndId();
        String serviceName = result.getServiceName();
        ServiceResultStatus status = result.getStatus();
        
        log.info("ServiceResult received: service={}, E2E={}, status={}", 
            serviceName, endToEndId, status);
        
        // Get cached PaymentEvent
        PaymentEvent event = eventCache.get(endToEndId);
        
        // On FAIL/ERROR: stop progression and send RJCT status
        if (status == ServiceResultStatus.FAIL || status == ServiceResultStatus.ERROR) {
            log.warn("Halting progression for E2E={} due to status={} from service={}", 
                endToEndId, status, serviceName);
            
            // Send RJCT status notification
            if (notificationService != null && event != null) {
                String rejectionReason = "Payment rejected at " + serviceName + " stage";
                notificationService.publishStatus(event, Pacs002Status.RJCT, 
                    "RJCT", rejectionReason);
            }
            
            return;
        }
        
        // If we don't have the PaymentEvent cached yet, we can't forward
        if (event == null) {
            log.warn("No cached PaymentEvent for E2E={}; cannot forward to next step", endToEndId);
            return;
        }
        
        // Route to next step based on service name
        String svc = serviceName.trim().toLowerCase();
        
        switch (svc) {
            case "account_validation":
                // Account validation publishes enriched PaymentEvent directly to routing_validation topic.
                // Orchestrator just waits for routing_validation ServiceResult.
                log.info("Account validation PASSED. Waiting for routing_validation ServiceResult.");
                
                // Send ACCP status (accepted for processing)
                if (notificationService != null) {
                    notificationService.publishStatus(event, Pacs002Status.ACCP, 
                        "ACCP", "Payment accepted for processing");
                }
                break;
                
            case "routing_validation":
                // Routing validation publishes routed PaymentEvent directly to sanctions_check INPUT topic.
                // Orchestrator just waits for sanctions_check ServiceResult.
                log.info("Routing validation PASSED. Waiting for sanctions_check ServiceResult.");
                
                // Send ACCC status (accepted after customer profile validation)
                if (notificationService != null) {
                    notificationService.publishStatus(event, Pacs002Status.ACCC, 
                        "ACCC", "Payment accepted after customer profile validation");
                }
                break;
                
            case "sanctions_check":
                // Sanctions check passed, forward to balance check
                log.info("Sanctions check PASSED. Forwarding to balance_check.");
                
                // Send PDNG status (pending further checks)
                if (notificationService != null) {
                    notificationService.publishStatus(event, Pacs002Status.PDNG, 
                        "PDNG", "Payment pending balance verification");
                }
                
                publishToStep(TOPIC_BALANCE_CHECK_IN, event);
                break;
                
            case "balance_check":
                // Balance check passed, forward to payment posting
                log.info("Balance check PASSED. Forwarding to payment_posting.");
                
                // Send ACSP status (accepted, settlement in process)
                if (notificationService != null) {
                    notificationService.publishStatus(event, Pacs002Status.ACSP, 
                        "ACSP", "Payment accepted, settlement in process");
                }
                
                publishToStep(TOPIC_PAYMENT_POSTING_IN, event);
                break;
                
            case "payment_posting":
                // Payment posting passed, emit final status
                log.info("Payment posting PASSED. Emitting final status.");
                
                // Send ACSC status (accepted, settlement completed)
                if (notificationService != null) {
                    notificationService.publishStatus(event, Pacs002Status.ACSC, 
                        "ACSC", "Payment accepted, settlement completed");
                }
                
                emitFinalStatus(event);
                break;
                
            default:
                log.warn("Unknown service name: {}", serviceName);
        }
    }
    
    /**
     * Publish PaymentEvent to a satellite input topic.
     */
    private void publishToStep(String topic, PaymentEvent event) {
        String endToEndId = event.getEndToEndId();
        
        log.info("Forwarding E2E={} to step topic={}", endToEndId, topic);
        
        try {
            ProducerRecord<String, PaymentEvent> record = new ProducerRecord<>(
                topic, endToEndId, event);
            eventProducer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Failed to publish PaymentEvent to topic={}, E2E={}", 
                        topic, endToEndId, exception);
                } else {
                    log.debug("Published PaymentEvent to topic={}, partition={}, offset={}, E2E={}", 
                        metadata.topic(), metadata.partition(), metadata.offset(), endToEndId);
                }
            });
        } catch (Exception e) {
            log.error("Error publishing PaymentEvent to topic={}, E2E={}", topic, endToEndId, e);
        }
    }
    
    /**
     * Emit final status once per payment (idempotent).
     */
    private void emitFinalStatus(PaymentEvent event) {
        String endToEndId = event.getEndToEndId();
        
        // Idempotency check: if already emitted, skip
        if (finalStatusEmitted.contains(endToEndId)) {
            log.debug("Final status already emitted for E2E={}, skipping", endToEndId);
            return;
        }
        
        finalStatusEmitted.add(endToEndId);
        
        // Update event status to ACCEPTED
        PaymentEvent finalEvent = PaymentEvent.builder()
            .msgId(event.getMsgId())
            .endToEndId(event.getEndToEndId())
            .transactionId(event.getTransactionId())
            .amount(event.getAmount())
            .currency(event.getCurrency())
            .direction(event.getDirection())
            .sourceMessageType(event.getSourceMessageType())
            .sourceMessageRaw(event.getSourceMessageRaw())
            .debtorAgent(event.getDebtorAgent())
            .creditorAgent(event.getCreditorAgent())
            .debtor(event.getDebtor())
            .creditor(event.getCreditor())
            .status(PaymentStatus.ACCEPTED)
            .valueDate(event.getValueDate())
            .settlementDate(event.getSettlementDate())
            .remittanceInfo(event.getRemittanceInfo())
            .purposeCode(event.getPurposeCode())
            .chargeBearer(event.getChargeBearer())
            .enrichmentContext(event.getEnrichmentContext())
            .routingContext(event.getRoutingContext())
            .createdTimestamp(event.getCreatedTimestamp())
            .lastUpdatedTimestamp(Instant.now().toString())
            .processingState("COMPLETED")
            .metadata(event.getMetadata())
            .build();
        
        log.info("Emitting FINAL PaymentEvent for E2E={}", endToEndId);
        
        try {
            ProducerRecord<String, PaymentEvent> record = new ProducerRecord<>(
                TOPIC_FINAL_STATUS, endToEndId, finalEvent);
            eventProducer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Failed to publish final status for E2E={}", endToEndId, exception);
                } else {
                    log.info("Published final status to topic={}, partition={}, offset={}, E2E={}", 
                        metadata.topic(), metadata.partition(), metadata.offset(), endToEndId);
                }
            });
        } catch (Exception e) {
            log.error("Error publishing final status for E2E={}", endToEndId, e);
        }
    }
}

