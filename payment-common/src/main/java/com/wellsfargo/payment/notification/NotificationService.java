package com.wellsfargo.payment.notification;

import com.wellsfargo.payment.canonical.PaymentEvent;
import com.wellsfargo.payment.canonical.enums.Pacs002Status;
import com.wellsfargo.payment.canonical.enums.ReturnReasonCode;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Properties;

/**
 * Notification Service for publishing PACS.002 status reports and PACS.004 payment returns.
 * 
 * This service publishes payment status updates to the instructing bank
 * via the notification Kafka topic. Each status update is sent as a PACS.002
 * XML message. Payment returns are sent as PACS.004 XML messages.
 */
public class NotificationService {
    
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final String TOPIC_NOTIFICATION = "payments.notification";
    private static final String TOPIC_PAYMENT_RETURN = "payments.return";
    
    private final String bootstrapServers;
    private KafkaProducer<String, String> producer;
    private final Pacs002Generator pacs002Generator = new Pacs002Generator();
    private final Pacs004Generator pacs004Generator = new Pacs004Generator();
    private String lastPacs004Xml; // Store last generated PACS.004 for message ID extraction
    
    /**
     * Constructor.
     * 
     * @param bootstrapServers Kafka bootstrap servers
     */
    public NotificationService(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
        init();
    }
    
    /**
     * Initialize Kafka producer.
     */
    private void init() {
        log.info("Initializing Notification Service");
        
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        
        producer = new KafkaProducer<>(props);
        
        log.info("Notification Service initialized");
    }
    
    /**
     * Shutdown and close Kafka producer.
     */
    public void shutdown() {
        log.info("Shutting down Notification Service");
        if (producer != null) {
            producer.close();
        }
    }
    
    /**
     * Publish PACS.002 status report.
     * 
     * @param event PaymentEvent
     * @param status PACS.002 status code
     * @param reasonCode Optional reason code
     * @param additionalInfo Optional additional information
     */
    public void publishStatus(PaymentEvent event, Pacs002Status status, 
                              String reasonCode, String additionalInfo) {
        String endToEndId = event.getEndToEndId();
        
        try {
            // Generate PACS.002 XML
            String pacs002Xml = pacs002Generator.generatePacs002(event, status, reasonCode, additionalInfo);
            
            // Publish to notification topic
            ProducerRecord<String, String> record = new ProducerRecord<>(
                TOPIC_NOTIFICATION, endToEndId, pacs002Xml);
            
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Failed to publish PACS.002 status - E2E={}, status={}", 
                        endToEndId, status, exception);
                } else {
                    log.info("Published PACS.002 status - E2E={}, status={}, topic={}, partition={}, offset={}", 
                        endToEndId, status, metadata.topic(), metadata.partition(), metadata.offset());
                }
            });
            
        } catch (Exception e) {
            log.error("Error publishing PACS.002 status - E2E={}, status={}", 
                endToEndId, status, e);
        }
    }
    
    /**
     * Publish PACS.002 status report with default reason code.
     */
    public void publishStatus(PaymentEvent event, Pacs002Status status) {
        publishStatus(event, status, null, null);
    }
    
    /**
     * Publish PACS.002 status report with custom additional info.
     */
    public void publishStatus(PaymentEvent event, Pacs002Status status, String additionalInfo) {
        publishStatus(event, status, null, additionalInfo);
    }
    
    /**
     * Publish PACS.004 payment return message.
     * 
     * @param event Original PaymentEvent
     * @param returnReasonCode ISO 20022 return reason code
     * @param additionalInfo Additional information about the return
     */
    public void publishPaymentReturn(PaymentEvent event, ReturnReasonCode returnReasonCode, 
                                     String additionalInfo) {
        String endToEndId = event.getEndToEndId();
        
        try {
            // Generate PACS.004 XML (store for message ID extraction)
            lastPacs004Xml = pacs004Generator.generatePacs004(event, returnReasonCode, additionalInfo);
            String pacs004Xml = lastPacs004Xml;
            
            // Publish to payment return topic
            ProducerRecord<String, String> record = new ProducerRecord<>(
                TOPIC_PAYMENT_RETURN, endToEndId, pacs004Xml);
            
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Failed to publish PACS.004 return - E2E={}, reason={}", 
                        endToEndId, returnReasonCode, exception);
                } else {
                    log.info("Published PACS.004 return - E2E={}, reason={}, topic={}, partition={}, offset={}", 
                        endToEndId, returnReasonCode, metadata.topic(), metadata.partition(), metadata.offset());
                }
            });
            
            // Also send RJCT status via PACS.002 to indicate rejection
            publishStatus(event, Pacs002Status.RJCT, 
                returnReasonCode.getCode(), "Payment returned: " + additionalInfo);
            
        } catch (Exception e) {
            log.error("Error publishing PACS.004 return - E2E={}, reason={}", 
                endToEndId, returnReasonCode, e);
        }
    }
    
    /**
     * Publish PACS.004 payment return message with reason code string.
     * 
     * @param event Original PaymentEvent
     * @param returnReasonCodeString ISO 20022 return reason code string (e.g., "AC01", "RR01")
     * @param additionalInfo Additional information about the return
     */
    public void publishPaymentReturn(PaymentEvent event, String returnReasonCodeString, 
                                     String additionalInfo) {
        try {
            ReturnReasonCode returnReasonCode = ReturnReasonCode.fromCode(returnReasonCodeString);
            publishPaymentReturn(event, returnReasonCode, additionalInfo);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown return reason code: {}, using NARR", returnReasonCodeString);
            publishPaymentReturn(event, ReturnReasonCode.NARR, additionalInfo);
        }
    }
    
    /**
     * Publish PACS.004 payment return message with fees and settlement date.
     * 
     * @param event Original PaymentEvent
     * @param returnReasonCode ISO 20022 return reason code
     * @param additionalInfo Additional information about the return
     * @param returnAmount Return amount (may be less than original if fees deducted)
     * @param returnFees Processing fees (if any)
     * @param settlementDate Settlement date for the return
     */
    public void publishPaymentReturnWithFees(PaymentEvent event, ReturnReasonCode returnReasonCode,
                                             String additionalInfo, String returnAmount,
                                             String returnFees, String settlementDate) {
        String endToEndId = event.getEndToEndId();
        
        try {
            // Generate PACS.004 XML with fees
            lastPacs004Xml = pacs004Generator.generatePacs004(event, returnReasonCode.getCode(), 
                additionalInfo, returnAmount, returnFees, settlementDate);
            
            // Publish to payment return topic
            ProducerRecord<String, String> record = new ProducerRecord<>(
                TOPIC_PAYMENT_RETURN, endToEndId, lastPacs004Xml);
            
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Failed to publish PACS.004 return with fees - E2E={}, reason={}", 
                        endToEndId, returnReasonCode, exception);
                } else {
                    log.info("Published PACS.004 return with fees - E2E={}, reason={}, amount={}, fees={}, topic={}, partition={}, offset={}", 
                        endToEndId, returnReasonCode, returnAmount, returnFees, 
                        metadata.topic(), metadata.partition(), metadata.offset());
                }
            });
            
            // Also send RJCT status via PACS.002 to indicate rejection
            publishStatus(event, Pacs002Status.RJCT, 
                returnReasonCode.getCode(), "Payment returned: " + additionalInfo);
            
        } catch (Exception e) {
            log.error("Error publishing PACS.004 return with fees - E2E={}, reason={}", 
                endToEndId, returnReasonCode, e);
        }
    }
    
    /**
     * Publish camt.029 Resolution of Investigation message.
     * 
     * @param camt029Xml camt.029 XML message
     * @param endToEndId End-to-end ID for Kafka key
     */
    public void publishCamt029(String camt029Xml, String endToEndId) {
        try {
            // Publish to notification topic (same as PACS.002)
            ProducerRecord<String, String> record = new ProducerRecord<>(
                TOPIC_NOTIFICATION, endToEndId, camt029Xml);
            
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Failed to publish camt.029 - E2E={}", endToEndId, exception);
                } else {
                    log.info("Published camt.029 - E2E={}, topic={}, partition={}, offset={}", 
                        endToEndId, metadata.topic(), metadata.partition(), metadata.offset());
                }
            });
            
        } catch (Exception e) {
            log.error("Error publishing camt.029 - E2E={}", endToEndId, e);
        }
    }
    
    /**
     * Get last generated PACS.004 XML (for message ID extraction).
     */
    public String getLastPacs004Xml() {
        return lastPacs004Xml;
    }
}

