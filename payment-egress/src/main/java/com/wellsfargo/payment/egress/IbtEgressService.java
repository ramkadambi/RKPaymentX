package com.wellsfargo.payment.egress;

import com.wellsfargo.payment.canonical.PaymentEvent;
import com.wellsfargo.payment.canonical.enums.RoutingNetwork;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * IBT (Internal Bank Transfer) Egress Service.
 * 
 * Handles:
 * - Internal settlement (no external message format)
 * 
 * Consumes final PaymentEvent from payments.final.status.
 * Performs internal settlement based on RoutingContext.
 * Publishes to egress.ibt.settlement.
 */
@Service
public class IbtEgressService extends BaseEgressService {
    
    private static final Logger log = LoggerFactory.getLogger(IbtEgressService.class);
    private static final String TOPIC_INPUT = "payments.final.status";
    private static final String TOPIC_OUTPUT = "egress.ibt.settlement";
    
    @Value("${kafka.bootstrap.servers:localhost:9092}")
    private String bootstrapServers;
    
    @Value("${kafka.consumer.group-id:ibt-egress-group}")
    private String groupId;
    
    @Value("${kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;
    
    private KafkaConsumer<String, PaymentEvent> consumer;
    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    public IbtEgressService(@Value("${kafka.bootstrap.servers:localhost:9092}") String bootstrapServers) {
        super(bootstrapServers, RoutingNetwork.INTERNAL, TOPIC_OUTPUT);
    }
    
    @PostConstruct
    public void init() {
        log.info("Initializing IBT Egress Service");
        
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
        
        // Start processing thread
        running.set(true);
        executorService = Executors.newSingleThreadExecutor();
        executorService.submit(this::processEvents);
        
        log.info("IBT Egress Service initialized and started");
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down IBT Egress Service");
        
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
        
        close();
        
        log.info("IBT Egress Service shut down");
    }
    
    /**
     * Process PaymentEvents from Kafka.
     */
    private void processEvents() {
        log.info("Starting IBT egress event processing thread");
        
        while (running.get()) {
            try {
                ConsumerRecords<String, PaymentEvent> records = consumer.poll(Duration.ofSeconds(1));
                
                for (ConsumerRecord<String, PaymentEvent> record : records) {
                    try {
                        PaymentEvent event = record.value();
                        if (event != null && shouldHandle(event)) {
                            handleEvent(event);
                            consumer.commitSync();
                        } else if (event != null) {
                            // Not for IBT, skip
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
        
        log.info("IBT egress event processing thread stopped");
    }
    
    /**
     * Handle a PaymentEvent: perform internal settlement and publish.
     */
    private void handleEvent(PaymentEvent event) {
        String endToEndId = event.getEndToEndId();
        
        log.info("Processing IBT egress: E2E={}, Amount={} {}", 
            endToEndId, event.getAmount(), event.getCurrency());
        
        String settlementRecord = generateMessage(event);
        
        if (settlementRecord != null) {
            publishMessage(event, settlementRecord);
        } else {
            log.error("Failed to generate IBT settlement record for E2E={}", endToEndId);
        }
    }
    
    /**
     * Generate IBT internal settlement record from PaymentEvent.
     * 
     * IBT doesn't generate external messages - it performs internal settlement.
     * This generates a settlement record for internal systems.
     * 
     * In production, this would:
     * 1. Create internal settlement record
     * 2. Update internal ledger
     * 3. Generate settlement confirmation
     */
    @Override
    protected String generateMessage(PaymentEvent event) {
        try {
            // IBT internal settlement record (JSON format for simplicity)
            // In production, this would be a structured settlement record
            
            StringBuilder settlement = new StringBuilder();
            settlement.append("{\n");
            settlement.append("  \"settlement_type\": \"IBT\",\n");
            settlement.append("  \"end_to_end_id\": \"").append(event.getEndToEndId()).append("\",\n");
            settlement.append("  \"transaction_id\": \"").append(event.getTransactionId() != null ? event.getTransactionId() : "").append("\",\n");
            settlement.append("  \"msg_id\": \"").append(event.getMsgId()).append("\",\n");
            settlement.append("  \"amount\": \"").append(event.getAmount()).append("\",\n");
            settlement.append("  \"currency\": \"").append(event.getCurrency()).append("\",\n");
            
            if (event.getDebtorAgent() != null) {
                settlement.append("  \"debtor_agent\": {\n");
                settlement.append("    \"id_scheme\": \"").append(event.getDebtorAgent().getIdScheme()).append("\",\n");
                settlement.append("    \"id_value\": \"").append(event.getDebtorAgent().getIdValue()).append("\"\n");
                settlement.append("  },\n");
            }
            
            if (event.getCreditorAgent() != null) {
                settlement.append("  \"creditor_agent\": {\n");
                settlement.append("    \"id_scheme\": \"").append(event.getCreditorAgent().getIdScheme()).append("\",\n");
                settlement.append("    \"id_value\": \"").append(event.getCreditorAgent().getIdValue()).append("\"\n");
                settlement.append("  },\n");
            }
            
            // Add settlement accounts from enrichment context
            if (event.getEnrichmentContext() != null && 
                event.getEnrichmentContext().getSettlementAccounts() != null) {
                var settlementAccounts = event.getEnrichmentContext().getSettlementAccounts();
                settlement.append("  \"debit_account\": \"").append(settlementAccounts.get("debit_account")).append("\",\n");
                settlement.append("  \"credit_account\": \"").append(settlementAccounts.get("credit_account")).append("\",\n");
            }
            
            settlement.append("  \"settlement_timestamp\": \"").append(event.getLastUpdatedTimestamp()).append("\",\n");
            settlement.append("  \"status\": \"SETTLED\"\n");
            settlement.append("}");
            
            return settlement.toString();
            
        } catch (Exception e) {
            log.error("Failed to generate IBT settlement record", e);
            return null;
        }
    }
}

