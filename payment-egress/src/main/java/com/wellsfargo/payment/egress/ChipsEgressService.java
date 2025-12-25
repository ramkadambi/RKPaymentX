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
 * CHIPS Egress Service.
 * 
 * Handles:
 * - pacs.009 (Financial Institution Credit Transfer)
 * 
 * Consumes final PaymentEvent from payments.final.status.
 * Generates CHIPS outbound messages based on RoutingContext.
 * Publishes to egress.chips.out.
 */
@Service
public class ChipsEgressService extends BaseEgressService {
    
    private static final Logger log = LoggerFactory.getLogger(ChipsEgressService.class);
    private static final String TOPIC_INPUT = "payments.final.status";
    private static final String TOPIC_OUTPUT = "egress.chips.out";
    
    @Value("${kafka.bootstrap.servers:localhost:9092}")
    private String bootstrapServers;
    
    @Value("${kafka.consumer.group-id:chips-egress-group}")
    private String groupId;
    
    @Value("${kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;
    
    private KafkaConsumer<String, PaymentEvent> consumer;
    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    public ChipsEgressService(@Value("${kafka.bootstrap.servers:localhost:9092}") String bootstrapServers) {
        super(bootstrapServers, RoutingNetwork.CHIPS, TOPIC_OUTPUT);
    }
    
    @PostConstruct
    public void init() {
        log.info("Initializing CHIPS Egress Service");
        
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
        
        log.info("CHIPS Egress Service initialized and started");
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down CHIPS Egress Service");
        
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
        
        log.info("CHIPS Egress Service shut down");
    }
    
    /**
     * Process PaymentEvents from Kafka.
     */
    private void processEvents() {
        log.info("Starting CHIPS egress event processing thread");
        
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
                            // Not for CHIPS, skip
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
        
        log.info("CHIPS egress event processing thread stopped");
    }
    
    /**
     * Handle a PaymentEvent: generate CHIPS message and publish.
     */
    private void handleEvent(PaymentEvent event) {
        String endToEndId = event.getEndToEndId();
        
        log.info("Processing CHIPS egress: E2E={}, Amount={} {}", 
            endToEndId, event.getAmount(), event.getCurrency());
        
        String message = generateMessage(event);
        
        if (message != null) {
            publishMessage(event, message);
        } else {
            log.error("Failed to generate CHIPS message for E2E={}", endToEndId);
        }
    }
    
    /**
     * Generate CHIPS outbound message (pacs.009) from PaymentEvent.
     * 
     * This is a mock implementation. In production, this would:
     * 1. Build XML/JSON structure according to ISO 20022 pacs.009 standard
     * 2. Use CHIPS UID or BIC for agent identification
     * 3. Populate all required fields from PaymentEvent
     * 4. Return formatted message
     */
    @Override
    protected String generateMessage(PaymentEvent event) {
        try {
            // Mock message generation: In production, build actual XML/JSON
            // Structure would be:
            // - GrpHdr (Group Header) with MsgId, CreDtTm, etc.
            // - FICdtTrf (Financial Institution Credit Transfer)
            // - CdtTrfTxInf with PmtId, IntrBkSttlmAmt
            // - DbtrAgt and CdtrAgt with CHIPS UID or BIC
            
            StringBuilder message = new StringBuilder();
            message.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            message.append("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.009\">\n");
            message.append("  <GrpHdr>\n");
            message.append("    <MsgId>").append(event.getMsgId()).append("</MsgId>\n");
            message.append("    <CreDtTm>").append(event.getCreatedTimestamp()).append("</CreDtTm>\n");
            message.append("  </GrpHdr>\n");
            message.append("  <FICdtTrf>\n");
            message.append("    <CdtTrfTxInf>\n");
            message.append("      <PmtId>\n");
            message.append("        <EndToEndId>").append(event.getEndToEndId()).append("</EndToEndId>\n");
            message.append("      </PmtId>\n");
            message.append("      <IntrBkSttlmAmt Ccy=\"").append(event.getCurrency()).append("\">")
                .append(event.getAmount()).append("</IntrBkSttlmAmt>\n");
            
            if (event.getDebtorAgent() != null) {
                message.append("      <DbtrAgt>\n");
                message.append("        <FinInstnId>\n");
                // CHIPS uses CHIPS UID or BIC
                if ("CHIPS_UID".equals(event.getDebtorAgent().getIdScheme())) {
                    message.append("          <Othr>\n");
                    message.append("            <Id>").append(event.getDebtorAgent().getIdValue()).append("</Id>\n");
                    message.append("            <SchmeNm>\n");
                    message.append("              <Cd>CHIPS</Cd>\n");
                    message.append("            </SchmeNm>\n");
                    message.append("          </Othr>\n");
                } else {
                    message.append("          <BICFI>").append(event.getDebtorAgent().getIdValue()).append("</BICFI>\n");
                }
                message.append("        </FinInstnId>\n");
                message.append("      </DbtrAgt>\n");
            }
            
            if (event.getCreditorAgent() != null) {
                message.append("      <CdtrAgt>\n");
                message.append("        <FinInstnId>\n");
                // CHIPS uses CHIPS UID or BIC
                if ("CHIPS_UID".equals(event.getCreditorAgent().getIdScheme())) {
                    message.append("          <Othr>\n");
                    message.append("            <Id>").append(event.getCreditorAgent().getIdValue()).append("</Id>\n");
                    message.append("            <SchmeNm>\n");
                    message.append("              <Cd>CHIPS</Cd>\n");
                    message.append("            </SchmeNm>\n");
                    message.append("          </Othr>\n");
                } else {
                    message.append("          <BICFI>").append(event.getCreditorAgent().getIdValue()).append("</BICFI>\n");
                }
                message.append("        </FinInstnId>\n");
                message.append("      </CdtrAgt>\n");
            }
            
            message.append("    </CdtTrfTxInf>\n");
            message.append("  </FICdtTrf>\n");
            message.append("</Document>");
            
            return message.toString();
            
        } catch (Exception e) {
            log.error("Failed to generate CHIPS message", e);
            return null;
        }
    }
}

