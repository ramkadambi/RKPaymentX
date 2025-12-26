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
 * SWIFT Ingress Service.
 * 
 * Handles:
 * - pacs.008 (Customer Credit Transfer)
 * - pacs.009 (Financial Institution Credit Transfer)
 * 
 * Parses SWIFT messages and converts to canonical PaymentEvent.
 * Publishes to payments.orchestrator.in.
 */
@Service
public class SwiftIngressService extends BaseIngressService {
    
    private static final Logger log = LoggerFactory.getLogger(SwiftIngressService.class);
    private static final String TOPIC_INPUT = "ingress.swift.in";
    
    @Value("${kafka.bootstrap.servers:localhost:9092}")
    private String bootstrapServers;
    
    @Value("${kafka.consumer.group-id:swift-ingress-group}")
    private String groupId;
    
    @Value("${kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;
    
    private KafkaConsumer<String, String> consumer;
    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    public SwiftIngressService(@Value("${kafka.bootstrap.servers:localhost:9092}") String bootstrapServers) {
        // SWIFT can handle both pacs.008 and pacs.009
        // Default to pacs.008, but parseMessage will determine actual type
        super(bootstrapServers, MessageSource.ISO20022_PACS008, PaymentDirection.INBOUND);
    }
    
    @PostConstruct
    public void init() {
        log.info("Initializing SWIFT Ingress Service");
        
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
        
        log.info("SWIFT Ingress Service initialized and started");
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down SWIFT Ingress Service");
        
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
        
        log.info("SWIFT Ingress Service shut down");
    }
    
    /**
     * Process messages from Kafka.
     */
    private void processMessages() {
        log.info("Starting SWIFT ingress message processing thread");
        
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
        
        log.info("SWIFT ingress message processing thread stopped");
    }
    
    /**
     * Handle incoming message.
     */
    private void handleMessage(String rawMessage) {
        log.info("Received SWIFT message: length={}", rawMessage.length());
        
        // Check if this is a cancellation message (camt.055 or camt.056)
        if (routeCancellationMessageIfNeeded(rawMessage)) {
            log.info("Message routed to cancellation handler - skipping payment parsing");
            return;
        }
        
        PaymentEvent event = parseMessage(rawMessage);
        
        if (event != null) {
            publishToOrchestrator(event);
        } else {
            log.error("Failed to parse SWIFT message");
        }
    }
    
    /**
     * Parse SWIFT message (pacs.008 or pacs.009) and convert to PaymentEvent.
     * 
     * This is a mock implementation. In production, this would:
     * 1. Parse XML/JSON pacs.008 or pacs.009 message
     * 2. Extract routing-relevant fields
     * 3. Map to canonical PaymentEvent
     * 
     * For now, extracts basic fields from a simple format.
     */
    @Override
    protected PaymentEvent parseMessage(String rawMessage) {
        try {
            // Mock parsing: In production, this would parse actual pacs.008/pacs.009 XML/JSON
            // For now, assume a simple format or JSON structure
            
            // Extract routing-relevant fields
            // In production, use XML/JSON parser to extract:
            // - MsgId from GrpHdr/MsgId
            // - EndToEndId from CdtTrfTxInf/PmtId/EndToEndId
            // - Amount from CdtTrfTxInf/IntrBkSttlmAmt
            // - Currency from CdtTrfTxInf/IntrBkSttlmAmt/@Ccy
            // - DebtorAgent from CdtTrfTxInf/DbtrAgt/FinInstnId/BICFI
            // - CreditorAgent from CdtTrfTxInf/CdtrAgt/FinInstnId/BICFI
            // - Debtor from CdtTrfTxInf/Dbtr
            // - Creditor from CdtTrfTxInf/Cdtr
            
            // Mock implementation: Generate from raw message hash
            String msgId = "SWIFT-" + Math.abs(rawMessage.hashCode());
            String endToEndId = "E2E-SWIFT-" + Math.abs(rawMessage.hashCode());
            
            // Create base event
            PaymentEvent event = createBasePaymentEvent(msgId, endToEndId, rawMessage);
            
            // Mock agent extraction (in production, parse from message)
            Agent debtorAgent = Agent.builder()
                .idScheme("BIC")
                .idValue("DEUTDEFF") // Mock: would come from message
                .country("DE")
                .build();
            
            Agent creditorAgent = Agent.builder()
                .idScheme("BIC")
                .idValue("WFBIUS6SXXX") // Mock: would come from message
                .country("US")
                .build();
            
            // Mock party extraction (in production, parse from message)
            Party debtor = Party.builder()
                .name("Mock Debtor")
                .country("DE")
                .build();
            
            Party creditor = Party.builder()
                .name("Mock Creditor")
                .country("US")
                .build();
            
            // Build complete event
            return PaymentEvent.builder()
                .msgId(event.getMsgId())
                .endToEndId(event.getEndToEndId())
                .sourceMessageType(event.getSourceMessageType())
                .sourceMessageRaw(event.getSourceMessageRaw())
                .direction(event.getDirection())
                .status(event.getStatus())
                .createdTimestamp(event.getCreatedTimestamp())
                .lastUpdatedTimestamp(event.getLastUpdatedTimestamp())
                .amount(new BigDecimal("1000.00")) // Mock: would come from message
                .currency("USD") // Mock: would come from message
                .debtorAgent(debtorAgent)
                .creditorAgent(creditorAgent)
                .debtor(debtor)
                .creditor(creditor)
                .build();
            
        } catch (Exception e) {
            log.error("Failed to parse SWIFT message", e);
            return null;
        }
    }
}

