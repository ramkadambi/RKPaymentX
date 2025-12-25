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
 * SWIFT Egress Service.
 * 
 * Handles:
 * - pacs.008 (Customer Credit Transfer) - for customer payments
 * - pacs.009 (Financial Institution Credit Transfer) - for interbank transfers
 * 
 * Consumes final PaymentEvent from payments.final.status.
 * Generates SWIFT outbound messages based on RoutingContext.
 * Publishes to egress.swift.out.
 */
@Service
public class SwiftEgressService extends BaseEgressService {
    
    private static final Logger log = LoggerFactory.getLogger(SwiftEgressService.class);
    private static final String TOPIC_INPUT = "payments.final.status";
    private static final String TOPIC_OUTPUT = "egress.swift.out";
    
    @Value("${kafka.bootstrap.servers:localhost:9092}")
    private String bootstrapServers;
    
    @Value("${kafka.consumer.group-id:swift-egress-group}")
    private String groupId;
    
    @Value("${kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;
    
    private KafkaConsumer<String, PaymentEvent> consumer;
    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    public SwiftEgressService(@Value("${kafka.bootstrap.servers:localhost:9092}") String bootstrapServers) {
        super(bootstrapServers, RoutingNetwork.SWIFT, TOPIC_OUTPUT);
    }
    
    @PostConstruct
    public void init() {
        log.info("Initializing SWIFT Egress Service");
        
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
        
        log.info("SWIFT Egress Service initialized and started");
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down SWIFT Egress Service");
        
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
        
        log.info("SWIFT Egress Service shut down");
    }
    
    /**
     * Process PaymentEvents from Kafka.
     */
    private void processEvents() {
        log.info("Starting SWIFT egress event processing thread");
        
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
                            // Not for SWIFT, skip
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
        
        log.info("SWIFT egress event processing thread stopped");
    }
    
    /**
     * Handle a PaymentEvent: generate SWIFT message and publish.
     */
    private void handleEvent(PaymentEvent event) {
        String endToEndId = event.getEndToEndId();
        
        log.info("Processing SWIFT egress: E2E={}, Amount={} {}", 
            endToEndId, event.getAmount(), event.getCurrency());
        
        String message = generateMessage(event);
        
        if (message != null) {
            publishMessage(event, message);
        } else {
            log.error("Failed to generate SWIFT message for E2E={}", endToEndId);
        }
    }
    
    /**
     * Generate SWIFT outbound message (pacs.008 or pacs.009) from PaymentEvent.
     * 
     * This is a mock implementation. In production, this would:
     * 1. Determine message type (pacs.008 for customer payments, pacs.009 for interbank)
     * 2. Build XML/JSON structure according to ISO 20022 standard
     * 3. Populate all required fields from PaymentEvent
     * 4. Return formatted message
     */
    @Override
    protected String generateMessage(PaymentEvent event) {
        try {
            // Determine message type based on payment characteristics
            // pacs.008: Customer Credit Transfer (has debtor/creditor parties)
            // pacs.009: Financial Institution Credit Transfer (interbank only)
            boolean isCustomerPayment = event.getDebtor() != null || event.getCreditor() != null;
            String messageType = isCustomerPayment ? "pacs.008" : "pacs.009";
            
            // Mock message generation: In production, build actual XML/JSON
            // Structure would be:
            // - GrpHdr (Group Header) with MsgId, CreDtTm, etc.
            // - CdtTrfTxInf (Credit Transfer Transaction Information) for pacs.008
            //   or FICdtTrf (Financial Institution Credit Transfer) for pacs.009
            // - PmtId with EndToEndId
            // - IntrBkSttlmAmt with Amount and Currency
            // - DbtrAgt and CdtrAgt (Debtor/Creditor Agents) with BIC
            // - Dbtr and Cdtr (Debtor/Creditor Parties) for pacs.008
            
            StringBuilder message = new StringBuilder();
            message.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            message.append("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:").append(messageType).append("\">\n");
            message.append("  <GrpHdr>\n");
            message.append("    <MsgId>").append(event.getMsgId()).append("</MsgId>\n");
            message.append("    <CreDtTm>").append(event.getCreatedTimestamp()).append("</CreDtTm>\n");
            message.append("  </GrpHdr>\n");
            
            if ("pacs.008".equals(messageType)) {
                message.append("  <CdtTrfTxInf>\n");
                message.append("    <PmtId>\n");
                message.append("      <EndToEndId>").append(event.getEndToEndId()).append("</EndToEndId>\n");
                message.append("    </PmtId>\n");
                message.append("    <IntrBkSttlmAmt Ccy=\"").append(event.getCurrency()).append("\">")
                    .append(event.getAmount()).append("</IntrBkSttlmAmt>\n");
                
                if (event.getDebtorAgent() != null) {
                    message.append("    <DbtrAgt>\n");
                    message.append("      <FinInstnId>\n");
                    message.append("        <BICFI>").append(event.getDebtorAgent().getIdValue()).append("</BICFI>\n");
                    message.append("      </FinInstnId>\n");
                    message.append("    </DbtrAgt>\n");
                }
                
                if (event.getCreditorAgent() != null) {
                    message.append("    <CdtrAgt>\n");
                    message.append("      <FinInstnId>\n");
                    message.append("        <BICFI>").append(event.getCreditorAgent().getIdValue()).append("</BICFI>\n");
                    message.append("      </FinInstnId>\n");
                    message.append("    </CdtrAgt>\n");
                }
                
                if (event.getDebtor() != null) {
                    message.append("    <Dbtr>\n");
                    message.append("      <Nm>").append(event.getDebtor().getName()).append("</Nm>\n");
                    message.append("    </Dbtr>\n");
                }
                
                if (event.getCreditor() != null) {
                    message.append("    <Cdtr>\n");
                    message.append("      <Nm>").append(event.getCreditor().getName()).append("</Nm>\n");
                    message.append("    </Cdtr>\n");
                }
                
                message.append("  </CdtTrfTxInf>\n");
            } else {
                // pacs.009
                message.append("  <FICdtTrf>\n");
                message.append("    <GrpHdr>\n");
                message.append("      <MsgId>").append(event.getMsgId()).append("</MsgId>\n");
                message.append("    </GrpHdr>\n");
                message.append("    <CdtTrfTxInf>\n");
                message.append("      <PmtId>\n");
                message.append("        <EndToEndId>").append(event.getEndToEndId()).append("</EndToEndId>\n");
                message.append("      </PmtId>\n");
                message.append("      <IntrBkSttlmAmt Ccy=\"").append(event.getCurrency()).append("\">")
                    .append(event.getAmount()).append("</IntrBkSttlmAmt>\n");
                
                if (event.getDebtorAgent() != null) {
                    message.append("      <DbtrAgt>\n");
                    message.append("        <FinInstnId>\n");
                    message.append("          <BICFI>").append(event.getDebtorAgent().getIdValue()).append("</BICFI>\n");
                    message.append("        </FinInstnId>\n");
                    message.append("      </DbtrAgt>\n");
                }
                
                if (event.getCreditorAgent() != null) {
                    message.append("      <CdtrAgt>\n");
                    message.append("        <FinInstnId>\n");
                    message.append("          <BICFI>").append(event.getCreditorAgent().getIdValue()).append("</BICFI>\n");
                    message.append("        </FinInstnId>\n");
                    message.append("      </CdtrAgt>\n");
                }
                
                message.append("    </CdtTrfTxInf>\n");
                message.append("  </FICdtTrf>\n");
            }
            
            message.append("</Document>");
            
            return message.toString();
            
        } catch (Exception e) {
            log.error("Failed to generate SWIFT message", e);
            return null;
        }
    }
}

