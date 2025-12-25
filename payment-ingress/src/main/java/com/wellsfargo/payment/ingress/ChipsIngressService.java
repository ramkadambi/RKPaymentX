package com.wellsfargo.payment.ingress;

import com.wellsfargo.payment.canonical.Agent;
import com.wellsfargo.payment.canonical.Party;
import com.wellsfargo.payment.canonical.PaymentEvent;
import com.wellsfargo.payment.canonical.enums.MessageSource;
import com.wellsfargo.payment.canonical.enums.PaymentDirection;
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
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CHIPS Ingress Service.
 * 
 * Handles:
 * - pacs.008 (Customer Credit Transfer) - from Wells customers via API/portal/branch
 * - pacs.009 (Financial Institution Credit Transfer) - from other banks via CHIPS network
 * 
 * All messages are converted to canonical PaymentEvent (pacs.008-like, customer-centric format)
 * for consistent storage and processing within the payment ecosystem.
 * 
 * Parses CHIPS messages and converts to canonical PaymentEvent.
 * Publishes to payments.orchestrator.in.
 */
@Service
public class ChipsIngressService extends BaseIngressService {
    
    private static final Logger log = LoggerFactory.getLogger(ChipsIngressService.class);
    private static final String TOPIC_INPUT = "ingress.chips.in";
    
    @Value("${kafka.bootstrap.servers:localhost:9092}")
    private String bootstrapServers;
    
    @Value("${kafka.consumer.group-id:chips-ingress-group}")
    private String groupId;
    
    @Value("${kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;
    
    private KafkaConsumer<String, String> consumer;
    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    public ChipsIngressService(@Value("${kafka.bootstrap.servers:localhost:9092}") String bootstrapServers) {
        super(bootstrapServers, MessageSource.ISO20022_PACS009, PaymentDirection.INBOUND);
    }
    
    @PostConstruct
    public void init() {
        log.info("Initializing CHIPS Ingress Service");
        
        // Create consumer
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumerProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        
        consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Collections.singletonList(TOPIC_INPUT));
        
        // Start processing thread
        running.set(true);
        executorService = Executors.newSingleThreadExecutor();
        executorService.submit(this::processMessages);
        
        log.info("CHIPS Ingress Service initialized and started");
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down CHIPS Ingress Service");
        
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
        
        log.info("CHIPS Ingress Service shut down");
    }
    
    /**
     * Process messages from Kafka.
     */
    private void processMessages() {
        log.info("Starting CHIPS ingress message processing thread");
        
        while (running.get()) {
            try {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
                
                for (ConsumerRecord<String, String> record : records) {
                    try {
                        String rawMessage = record.value();
                        if (rawMessage != null) {
                            handleMessage(rawMessage);
                            consumer.commitSync();
                        }
                    } catch (Exception e) {
                        log.error("Error processing message: {}", record.key(), e);
                        // Continue processing other records
                    }
                }
            } catch (Exception e) {
                log.error("Error in message processing loop", e);
            }
        }
        
        log.info("CHIPS ingress message processing thread stopped");
    }
    
    /**
     * Handle incoming message.
     */
    private void handleMessage(String rawMessage) {
        log.info("Received CHIPS message: length={}", rawMessage.length());
        
        PaymentEvent event = parseMessage(rawMessage);
        
        if (event != null) {
            publishToOrchestrator(event);
        } else {
            log.error("Failed to parse CHIPS message");
        }
    }
    
    /**
     * Parse CHIPS message (pacs.008 or pacs.009) and convert to PaymentEvent.
     * 
     * Detects message type and parses accordingly:
     * - pacs.008: Customer Credit Transfer (from Wells customers via API/portal/branch)
     * - pacs.009: Financial Institution Credit Transfer (from other banks via CHIPS)
     * 
     * All messages are converted to canonical PaymentEvent (pacs.008-like, customer-centric)
     * for consistent storage and processing.
     * 
     * This is a mock implementation. In production, this would:
     * 1. Detect message type (check for FICdtTrf vs CdtTrfTxInf in XML, or message type indicator)
     * 2. Parse XML/JSON pacs.008 or pacs.009 message
     * 3. Extract routing-relevant fields
     * 4. Map to canonical PaymentEvent (always customer-centric, pacs.008-like structure)
     */
    @Override
    protected PaymentEvent parseMessage(String rawMessage) {
        try {
            // Detect message type: In production, parse XML/JSON to determine if pacs.008 or pacs.009
            // Check for presence of FICdtTrf (pacs.009) vs CdtTrfTxInf (pacs.008)
            boolean isPacs009 = rawMessage.contains("FICdtTrf") || rawMessage.contains("pacs.009");
            com.wellsfargo.payment.canonical.enums.MessageSource detectedMessageType = isPacs009 
                ? com.wellsfargo.payment.canonical.enums.MessageSource.ISO20022_PACS009
                : com.wellsfargo.payment.canonical.enums.MessageSource.ISO20022_PACS008;
            
            log.info("Detected CHIPS message type: {}", detectedMessageType.getValue());
            
            // Mock implementation: Generate from raw message hash
            String msgId = "CHIPS-" + Math.abs(rawMessage.hashCode());
            String endToEndId = "E2E-CHIPS-" + Math.abs(rawMessage.hashCode());
            
            // Create base event with detected message type
            PaymentEvent baseEvent = createBasePaymentEvent(msgId, endToEndId, rawMessage);
            
            // Mock agent extraction (in production, parse from message)
            // CHIPS uses CHIPS UID or BIC
            Agent debtorAgent = Agent.builder()
                .idScheme("CHIPS_UID")
                .idValue("0002") // Mock: would come from message (Chase CHIPS UID)
                .country("US")
                .build();
            
            Agent creditorAgent = Agent.builder()
                .idScheme("BIC")
                .idValue("WFBIUS6SXXX") // Mock: Wells Fargo
                .country("US")
                .build();
            
            // Build PaymentEvent with detected message type (override base event's sourceMessageType)
            var eventBuilder = PaymentEvent.builder()
                .msgId(baseEvent.getMsgId())
                .endToEndId(baseEvent.getEndToEndId())
                .sourceMessageType(detectedMessageType)  // Use detected message type
                .sourceMessageRaw(baseEvent.getSourceMessageRaw())
                .direction(baseEvent.getDirection())
                .status(baseEvent.getStatus())
                .createdTimestamp(baseEvent.getCreatedTimestamp())
                .lastUpdatedTimestamp(baseEvent.getLastUpdatedTimestamp())
                .amount(new BigDecimal("10000.00")) // Mock: would come from message
                .currency("USD") // Mock: would come from message
                .debtorAgent(debtorAgent)
                .creditorAgent(creditorAgent);
            
            // For pacs.008, include debtor/creditor parties (customer-centric)
            // For pacs.009, parties may not be present, but we store as customer-centric
            if (!isPacs009) {
                // pacs.008 includes debtor and creditor parties
                Party debtor = Party.builder()
                    .name("Customer Debtor") // Mock: would come from message
                    .country("US")
                    .build();
                
                Party creditor = Party.builder()
                    .name("Customer Creditor") // Mock: would come from message
                    .country("US")
                    .build();
                
                eventBuilder.debtor(debtor);
                eventBuilder.creditor(creditor);
            }
            
            return eventBuilder.build();
            
        } catch (Exception e) {
            log.error("Failed to parse CHIPS message", e);
            return null;
        }
    }
}

