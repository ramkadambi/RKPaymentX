package com.wellsfargo.payment.satellites.accountvalidation;

import com.wellsfargo.payment.canonical.*;
import com.wellsfargo.payment.canonical.enums.CreditorType;
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
 * Account Validation Service for payment processing.
 * 
 * This service:
 * 1. Consumes PaymentEvent from payments.step.account_validation
 * 2. Validates the account
 * 3. Enriches PaymentEvent with account validation data:
 *    - creditor_type (BANK / INDIVIDUAL)
 *    - fed_member
 *    - chips_member
 *    - preferred_correspondent
 *    - nostro_accounts_available
 *    - vostro_with_us
 * 4. Publishes enriched PaymentEvent to payments.step.routing_validation
 * 5. Publishes ServiceResult to service.results.account_validation
 * 6. Publishes errors to service.errors.account_validation
 * 
 * Constraints:
 * - Uses mock reference data (in-memory)
 * - No routing logic
 * - No Mongo writes yet
 */
@Service
public class AccountValidationService {
    
    private static final Logger log = LoggerFactory.getLogger(AccountValidationService.class);
    
    // Topic names
    private static final String TOPIC_INPUT = "payments.step.account_validation";
    private static final String TOPIC_RESULT = "service.results.account_validation";
    private static final String TOPIC_ERROR = "service.errors.account_validation";
    private static final String TOPIC_ROUTING = "payments.step.routing_validation";
    
    private static final String SERVICE_NAME = "account_validation";
    
    @Value("${kafka.bootstrap.servers:localhost:9092}")
    private String bootstrapServers;
    
    @Value("${kafka.consumer.group-id:account-validation-group}")
    private String groupId;
    
    @Value("${kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;
    
    // Kafka consumers and producers
    private KafkaConsumer<String, PaymentEvent> consumer;
    private KafkaProducer<String, PaymentEvent> eventProducer;
    private KafkaProducer<String, ServiceResult> resultProducer;
    
    // Account lookup service
    private final AccountLookupService accountLookupService = new AccountLookupService();
    
    // Thread management
    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    @PostConstruct
    public void init() {
        log.info("Initializing Account Validation Service");
        
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
        
        // Create event producer
        Properties eventProducerProps = new Properties();
        eventProducerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        eventProducerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        eventProducerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, 
            com.wellsfargo.payment.kafka.PaymentEventSerializer.class);
        eventProducerProps.put(ProducerConfig.ACKS_CONFIG, "1");
        eventProducerProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        eventProducerProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        eventProducerProps.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        eventProducerProps.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        
        eventProducer = new KafkaProducer<>(eventProducerProps);
        
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
        
        log.info("Account Validation Service initialized and started");
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down Account Validation Service");
        
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
        
        if (eventProducer != null) {
            eventProducer.close();
        }
        
        if (resultProducer != null) {
            resultProducer.close();
        }
        
        log.info("Account Validation Service shut down");
    }
    
    /**
     * Process PaymentEvents from Kafka.
     */
    private void processEvents() {
        log.info("Starting account validation event processing thread");
        
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
        
        log.info("Account validation event processing thread stopped");
    }
    
    /**
     * Handle a PaymentEvent: validate account and enrich with account data.
     */
    private void handleEvent(PaymentEvent event) {
        String endToEndId = event.getEndToEndId();
        String msgId = event.getMsgId();
        
        log.info("Received PaymentEvent: E2E={}, MsgId={}, Amount={} {}", 
            endToEndId, msgId, event.getAmount(), event.getCurrency());
        
        // Step 1: Validate account
        ServiceResultStatus validationStatus = validateAccount(event);
        log.info("Validation result: {} for E2E={}", validationStatus, endToEndId);
        
        // Step 2: Enrich PaymentEvent with account data (if validation PASS)
        PaymentEvent enrichedEvent = event;
        Map<String, Object> enrichmentData = null;
        
        if (validationStatus == ServiceResultStatus.PASS) {
            enrichmentData = enrichPaymentEvent(event);
            
            if (enrichmentData != null && !enrichmentData.isEmpty()) {
                enrichedEvent = createEnrichedEvent(event, enrichmentData);
                log.info("Enriched PaymentEvent: creditor_type={}, fed_member={}, chips_member={}, " +
                    "nostro_accounts_available={}, preferred_correspondent={}",
                    enrichmentData.get("creditor_type"),
                    enrichmentData.get("fed_member"),
                    enrichmentData.get("chips_member"),
                    enrichmentData.get("nostro_accounts_available"),
                    enrichmentData.get("preferred_correspondent"));
            } else {
                log.warn("Account not found in lookup for E2E={}, treating as validation failure", endToEndId);
                validationStatus = ServiceResultStatus.FAIL;
            }
        }
        
        // Step 3: Create ServiceResult
        ServiceResult result = ServiceResult.builder()
            .endToEndId(endToEndId)
            .serviceName(SERVICE_NAME)
            .status(validationStatus)
            .errorMessage(validationStatus != ServiceResultStatus.PASS 
                ? "Account validation failed" : null)
            .processingTimestamp(Instant.now().toString())
            .build();
        
        // Step 4: Publish ServiceResult
        publishServiceResult(result);
        
        // Step 5: If validation PASS, publish enriched PaymentEvent to routing_validation topic
        if (validationStatus == ServiceResultStatus.PASS && enrichmentData != null) {
            publishEnrichedEvent(enrichedEvent);
        } else {
            // Publish error
            publishError(result);
        }
        
        log.info("Completed processing E2E={}", endToEndId);
    }
    
    /**
     * Validate account (mock validation logic).
     * 
     * Current rule: FAIL if end_to_end_id ends with 'X' (case-insensitive), PASS otherwise.
     * In production, this would query account database and perform real validation.
     */
    private ServiceResultStatus validateAccount(PaymentEvent event) {
        String endToEndId = event.getEndToEndId();
        if (endToEndId != null && endToEndId.trim().toUpperCase().endsWith("X")) {
            return ServiceResultStatus.FAIL;
        }
        return ServiceResultStatus.PASS;
    }
    
    /**
     * Enrich PaymentEvent with account validation data.
     */
    private Map<String, Object> enrichPaymentEvent(PaymentEvent event) {
        String creditorBic = null;
        if (event.getCreditorAgent() != null) {
            creditorBic = event.getCreditorAgent().getIdValue();
        }
        
        if (creditorBic == null || creditorBic.trim().isEmpty()) {
            log.warn("No creditor BIC found in PaymentEvent");
            return null;
        }
        
        Optional<AccountEnrichmentData> lookupData = accountLookupService.lookupAccountEnrichment(creditorBic);
        if (lookupData.isEmpty()) {
            return null;
        }
        
        AccountEnrichmentData data = lookupData.get();
        
        // Build enrichment dictionary
        Map<String, Object> enrichment = new HashMap<>();
        enrichment.put("status", ServiceResultStatus.PASS.getValue());
        enrichment.put("creditor_type", data.getCreditorType().getValue());
        enrichment.put("fed_member", data.isFedMember());
        enrichment.put("chips_member", data.isChipsMember());
        enrichment.put("nostro_accounts_available", data.isNostroAccountsAvailable());
        enrichment.put("vostro_with_us", data.isVostroWithUs());
        if (data.getPreferredCorrespondent() != null) {
            enrichment.put("preferred_correspondent", data.getPreferredCorrespondent());
        }
        
        // Add correspondent bank information for non-FED-enabled banks
        if (data.isRequiresCorrespondent()) {
            enrichment.put("requires_correspondent", true);
            enrichment.put("correspondent_bank_bic", data.getCorrespondentBankBic());
            enrichment.put("correspondent_bank_name", data.getCorrespondentBankName());
            enrichment.put("correspondent_fed_enabled", data.isCorrespondentFedEnabled());
            enrichment.put("correspondent_chips_enabled", data.isCorrespondentChipsEnabled());
            log.info("Bank requires correspondent: creditor={}, correspondent={}", 
                creditorBic, data.getCorrespondentBankBic());
        } else {
            enrichment.put("requires_correspondent", false);
        }
        
        // Add bank category for routing decisions
        if (data.getBankCategory() != null) {
            enrichment.put("bank_category", data.getBankCategory());
        }
        
        return enrichment;
    }
    
    /**
     * Create enriched PaymentEvent with account validation data in EnrichmentContext.
     */
    private PaymentEvent createEnrichedEvent(PaymentEvent event, Map<String, Object> enrichmentData) {
        // Build or update EnrichmentContext
        EnrichmentContext.EnrichmentContextBuilder contextBuilder = EnrichmentContext.builder()
            .accountValidation(enrichmentData);
        
        // Preserve existing enrichment data
        if (event.getEnrichmentContext() != null) {
            EnrichmentContext existing = event.getEnrichmentContext();
            contextBuilder
                .bicLookup(existing.getBicLookup())
                .abaLookup(existing.getAbaLookup())
                .chipsLookup(existing.getChipsLookup())
                .sanctionsCheck(existing.getSanctionsCheck())
                .balanceCheck(existing.getBalanceCheck())
                .settlementAccounts(existing.getSettlementAccounts());
        }
        
        EnrichmentContext enrichedContext = contextBuilder.build();
        
        // Create enriched PaymentEvent
        return PaymentEvent.builder()
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
            .status(event.getStatus())
            .valueDate(event.getValueDate())
            .settlementDate(event.getSettlementDate())
            .remittanceInfo(event.getRemittanceInfo())
            .purposeCode(event.getPurposeCode())
            .chargeBearer(event.getChargeBearer())
            .enrichmentContext(enrichedContext)
            .routingContext(event.getRoutingContext())
            .createdTimestamp(event.getCreatedTimestamp())
            .lastUpdatedTimestamp(Instant.now().toString())
            .processingState(event.getProcessingState())
            .metadata(event.getMetadata())
            .build();
    }
    
    /**
     * Publish ServiceResult to Kafka result topic.
     */
    private void publishServiceResult(ServiceResult result) {
        String endToEndId = result.getEndToEndId();
        
        log.debug("Publishing ServiceResult to topic={}, E2E={}", TOPIC_RESULT, endToEndId);
        
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
     * Publish enriched PaymentEvent to Kafka routing validation topic.
     */
    private void publishEnrichedEvent(PaymentEvent event) {
        String endToEndId = event.getEndToEndId();
        
        log.info("Publishing enriched PaymentEvent to routing topic={}, E2E={}", TOPIC_ROUTING, endToEndId);
        
        try {
            ProducerRecord<String, PaymentEvent> record = new ProducerRecord<>(
                TOPIC_ROUTING, endToEndId, event);
            eventProducer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Failed to publish enriched PaymentEvent to topic={}, E2E={}", 
                        TOPIC_ROUTING, endToEndId, exception);
                } else {
                    log.info("Published enriched PaymentEvent to topic={}, partition={}, offset={}, E2E={}", 
                        metadata.topic(), metadata.partition(), metadata.offset(), endToEndId);
                }
            });
        } catch (Exception e) {
            log.error("Error publishing enriched PaymentEvent to topic={}, E2E={}", TOPIC_ROUTING, endToEndId, e);
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
}

