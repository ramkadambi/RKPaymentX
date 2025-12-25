package com.wellsfargo.payment.satellites.routingvalidation;

import com.wellsfargo.payment.canonical.*;
import com.wellsfargo.payment.canonical.enums.RoutingNetwork;
import com.wellsfargo.payment.canonical.enums.ServiceResultStatus;
import com.wellsfargo.payment.canonical.enums.Urgency;
import com.wellsfargo.payment.rules.RoutingRulesConfig;
import com.wellsfargo.payment.rules.RoutingRulesEngine;
import com.wellsfargo.payment.rules.RoutingRulesLoader;
import com.wellsfargo.payment.rules.RuleEvaluationResult;
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
 * Routing Validation Service for payment processing.
 * 
 * This service:
 * 1. Consumes enriched PaymentEvent from payments.step.routing_validation
 * 2. Uses routing_rulesV2.json via RoutingRulesEngine from payment-common
 * 3. Evaluates routing rules in priority order
 * 4. Determines selected_network (IBT/FED/CHIPS/SWIFT)
 * 5. Handles agent insertion/substitution if required
 * 6. Populates RoutingContext and RoutingTrace
 * 7. Publishes routed PaymentEvent to payments.step.sanctions_check
 * 8. Publishes ServiceResult to service.results.routing_validation
 * 9. Publishes errors to service.errors.routing_validation
 * 
 * Constraints:
 * - Uses routing rules engine from payment-common
 * - No Kafka topic creation
 * - No DB access
 * - Decision must be auditable (via RoutingTrace)
 */
@Service
public class RoutingValidationService {
    
    private static final Logger log = LoggerFactory.getLogger(RoutingValidationService.class);
    
    // Topic names
    private static final String TOPIC_INPUT = "payments.step.routing_validation";
    private static final String TOPIC_RESULT = "service.results.routing_validation";
    private static final String TOPIC_ERROR = "service.errors.routing_validation";
    private static final String TOPIC_SANCTIONS = "payments.step.sanctions_check";
    
    private static final String SERVICE_NAME = "routing_validation";
    
    @Value("${kafka.bootstrap.servers:localhost:9092}")
    private String bootstrapServers;
    
    @Value("${kafka.consumer.group-id:routing-validation-group}")
    private String groupId;
    
    @Value("${kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;
    
    // Kafka consumers and producers
    private KafkaConsumer<String, PaymentEvent> consumer;
    private KafkaProducer<String, PaymentEvent> eventProducer;
    private KafkaProducer<String, ServiceResult> resultProducer;
    
    // Routing rules engine
    private RoutingRulesEngine routingRulesEngine;
    
    // Thread management
    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    @PostConstruct
    public void init() {
        log.info("Initializing Routing Validation Service");
        
        // Load routing rules
        try {
            RoutingRulesLoader loader = new RoutingRulesLoader();
            RoutingRulesConfig config = loader.loadRules();
            routingRulesEngine = new RoutingRulesEngine(config);
            log.info("Loaded routing rules: {} rules", 
                config.getRules() != null ? config.getRules().size() : 0);
        } catch (Exception e) {
            log.error("Failed to load routing rules", e);
            throw new RuntimeException("Failed to initialize routing rules engine", e);
        }
        
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
        
        log.info("Routing Validation Service initialized and started");
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down Routing Validation Service");
        
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
        
        log.info("Routing Validation Service shut down");
    }
    
    /**
     * Process PaymentEvents from Kafka.
     */
    private void processEvents() {
        log.info("Starting routing validation event processing thread");
        
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
        
        log.info("Routing validation event processing thread stopped");
    }
    
    /**
     * Handle a PaymentEvent: apply routing rules and enrich with routing decisions.
     */
    private void handleEvent(PaymentEvent event) {
        String endToEndId = event.getEndToEndId();
        String msgId = event.getMsgId();
        
        log.info("Received PaymentEvent: E2E={}, MsgId={}, Amount={} {}", 
            endToEndId, msgId, event.getAmount(), event.getCurrency());
        
        // Validate that account_validation enrichment exists
        if (event.getEnrichmentContext() == null || 
            event.getEnrichmentContext().getAccountValidation() == null) {
            String errorMsg = String.format("PaymentEvent missing account_validation enrichment for E2E=%s", endToEndId);
            log.error(errorMsg);
            
            ServiceResult result = ServiceResult.builder()
                .endToEndId(endToEndId)
                .serviceName(SERVICE_NAME)
                .status(ServiceResultStatus.ERROR)
                .errorMessage(errorMsg)
                .processingTimestamp(Instant.now().toString())
                .build();
            
            publishError(result);
            return;
        }
        
        // Apply routing rules
        try {
            RuleEvaluationResult ruleResult = routingRulesEngine.evaluate(event);
            
            // Build RoutingContext from rule evaluation result
            RoutingContext routingContext = buildRoutingContext(event, ruleResult);
            
            // Create routed PaymentEvent with RoutingContext
            PaymentEvent routedEvent = createRoutedEvent(event, routingContext);
            
            log.info("Routing applied: network={}, rule={}, agent_chain_len={}", 
                routingContext.getSelectedNetwork() != null ? routingContext.getSelectedNetwork().getValue() : "NONE",
                routingContext.getRoutingRuleApplied(),
                routingContext.getAgentChain() != null ? routingContext.getAgentChain().size() : 0);
            
            // Publish routed PaymentEvent to next step (sanctions_check)
            publishRoutedEvent(routedEvent);
            
            // Publish ServiceResult
            ServiceResult result = ServiceResult.builder()
                .endToEndId(endToEndId)
                .serviceName(SERVICE_NAME)
                .status(ServiceResultStatus.PASS)
                .errorMessage(null)
                .processingTimestamp(Instant.now().toString())
                .build();
            
            publishServiceResult(result);
            
            log.info("Completed processing E2E={}", endToEndId);
            
        } catch (Exception e) {
            String errorMsg = String.format("ERROR processing E2E=%s: %s", endToEndId, e.getMessage());
            log.error(errorMsg, e);
            
            ServiceResult result = ServiceResult.builder()
                .endToEndId(endToEndId)
                .serviceName(SERVICE_NAME)
                .status(ServiceResultStatus.ERROR)
                .errorMessage(e.getMessage())
                .processingTimestamp(Instant.now().toString())
                .build();
            
            publishError(result);
        }
    }
    
    /**
     * Build RoutingContext from PaymentEvent and RuleEvaluationResult.
     */
    private RoutingContext buildRoutingContext(PaymentEvent event, RuleEvaluationResult ruleResult) {
        // Get existing routing context or create new one
        RoutingContext existingContext = event.getRoutingContext();
        
        // Build routing decision map
        Map<String, Object> routingDecision = new HashMap<>();
        if (ruleResult.getRoutingDecision() != null) {
            routingDecision.putAll(ruleResult.getRoutingDecision());
        } else {
            routingDecision.put("selected_network", 
                ruleResult.getSelectedNetwork() != null ? ruleResult.getSelectedNetwork().getValue() : "SWIFT");
            routingDecision.put("routing_rule_applied", ruleResult.getAppliedRuleId());
            
            // Add agent information
            Map<String, Object> senderBank = new HashMap<>();
            if (event.getDebtorAgent() != null) {
                senderBank.put("id_scheme", event.getDebtorAgent().getIdScheme());
                senderBank.put("id_value", event.getDebtorAgent().getIdValue());
                senderBank.put("name", event.getDebtorAgent().getName());
                senderBank.put("country", event.getDebtorAgent().getCountry());
            }
            routingDecision.put("sender_bank", senderBank);
            
            Map<String, Object> creditorBank = new HashMap<>();
            if (event.getCreditorAgent() != null) {
                creditorBank.put("id_scheme", event.getCreditorAgent().getIdScheme());
                creditorBank.put("id_value", event.getCreditorAgent().getIdValue());
                creditorBank.put("name", event.getCreditorAgent().getName());
                creditorBank.put("country", event.getCreditorAgent().getCountry());
            }
            routingDecision.put("creditor_bank", creditorBank);
        }
        
        // Build agent chain (preserve existing or create new)
        List<Agent> agentChain = new ArrayList<>();
        if (existingContext != null && existingContext.getAgentChain() != null) {
            agentChain.addAll(existingContext.getAgentChain());
        }
        
        // Note: Agent insertion/substitution would be handled here if rule actions specify it
        // For now, we preserve the existing agent chain
        
        // Build payment ecosystem context (for optimization rules)
        Map<String, Object> paymentEcosystem = new HashMap<>();
        if (existingContext != null && existingContext.getPaymentEcosystem() != null) {
            paymentEcosystem.putAll(existingContext.getPaymentEcosystem());
        } else {
            // Default values
            paymentEcosystem.put("chips_cutoff_passed", false);
            paymentEcosystem.put("chips_queue_depth", "NORMAL");
            paymentEcosystem.put("fed_cutoff_passed", false);
            paymentEcosystem.put("swift_cutoff_passed", false);
        }
        
        // Build next bank information
        Map<String, Object> nextBank = null;
        if (existingContext != null && existingContext.getNextBank() != null) {
            nextBank = new HashMap<>(existingContext.getNextBank());
        }
        
        // Create RoutingContext
        return RoutingContext.builder()
            .selectedNetwork(ruleResult.getSelectedNetwork() != null 
                ? ruleResult.getSelectedNetwork() 
                : RoutingNetwork.SWIFT)
            .routingRuleApplied(ruleResult.getAppliedRuleId())
            .routingRulePriority(ruleResult.getAppliedRulePriority())
            .agentChain(agentChain.isEmpty() ? null : agentChain)
            .routingDecision(routingDecision)
            .paymentEcosystem(paymentEcosystem.isEmpty() ? null : paymentEcosystem)
            .customerPreference(existingContext != null ? existingContext.getCustomerPreference() : null)
            .paymentUrgency(existingContext != null && existingContext.getPaymentUrgency() != null
                ? existingContext.getPaymentUrgency()
                : Urgency.NORMAL)
            .nextBank(nextBank)
            .routingTrace(ruleResult.getRoutingTrace())
            .build();
    }
    
    /**
     * Create routed PaymentEvent with RoutingContext.
     */
    private PaymentEvent createRoutedEvent(PaymentEvent event, RoutingContext routingContext) {
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
            .enrichmentContext(event.getEnrichmentContext())
            .routingContext(routingContext)
            .createdTimestamp(event.getCreatedTimestamp())
            .lastUpdatedTimestamp(Instant.now().toString())
            .processingState(event.getProcessingState())
            .metadata(event.getMetadata())
            .build();
    }
    
    /**
     * Publish routed PaymentEvent to Kafka sanctions check topic.
     */
    private void publishRoutedEvent(PaymentEvent event) {
        String endToEndId = event.getEndToEndId();
        
        log.info("Publishing routed PaymentEvent to sanctions topic={}, E2E={}", TOPIC_SANCTIONS, endToEndId);
        
        try {
            ProducerRecord<String, PaymentEvent> record = new ProducerRecord<>(
                TOPIC_SANCTIONS, endToEndId, event);
            eventProducer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Failed to publish routed PaymentEvent to topic={}, E2E={}", 
                        TOPIC_SANCTIONS, endToEndId, exception);
                } else {
                    log.info("Published routed PaymentEvent to topic={}, partition={}, offset={}, E2E={}", 
                        metadata.topic(), metadata.partition(), metadata.offset(), endToEndId);
                }
            });
        } catch (Exception e) {
            log.error("Error publishing routed PaymentEvent to topic={}, E2E={}", TOPIC_SANCTIONS, endToEndId, e);
        }
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

