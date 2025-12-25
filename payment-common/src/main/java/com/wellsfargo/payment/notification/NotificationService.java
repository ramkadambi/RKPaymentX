package com.wellsfargo.payment.notification;

import com.wellsfargo.payment.canonical.PaymentEvent;
import com.wellsfargo.payment.canonical.enums.Pacs002Status;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Properties;

/**
 * Notification Service for publishing PACS.002 status reports.
 * 
 * This service publishes payment status updates to the instructing bank
 * via the notification Kafka topic. Each status update is sent as a PACS.002
 * XML message.
 */
public class NotificationService {
    
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final String TOPIC_NOTIFICATION = "payments.notification";
    
    private final String bootstrapServers;
    private KafkaProducer<String, String> producer;
    private final Pacs002Generator pacs002Generator = new Pacs002Generator();
    
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
}

