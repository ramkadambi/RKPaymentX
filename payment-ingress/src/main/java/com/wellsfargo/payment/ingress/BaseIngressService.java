package com.wellsfargo.payment.ingress;

import com.wellsfargo.payment.canonical.PaymentEvent;
import com.wellsfargo.payment.canonical.enums.MessageSource;
import com.wellsfargo.payment.canonical.enums.PaymentDirection;
import com.wellsfargo.payment.canonical.enums.PaymentStatus;
import com.wellsfargo.payment.ingress.common.CancellationMessageDetector;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Properties;

/**
 * Base class for ingress services.
 * 
 * Provides common functionality for parsing rail-specific messages
 * and publishing to payments.orchestrator.in.
 */
public abstract class BaseIngressService {
    
    protected static final Logger log = LoggerFactory.getLogger(BaseIngressService.class);
    protected static final String TOPIC_ORCHESTRATOR_IN = "payments.orchestrator.in";
    protected static final String TOPIC_CANCELLATION_IN = "payments.cancellation.in";
    
    protected final KafkaProducer<String, PaymentEvent> producer;
    protected final KafkaProducer<String, String> cancellationProducer;
    protected final MessageSource messageSource;
    protected final PaymentDirection direction;
    
    protected BaseIngressService(String bootstrapServers, MessageSource messageSource, PaymentDirection direction) {
        this.messageSource = messageSource;
        this.direction = direction;
        
        // Create producer for PaymentEvents
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, 
            com.wellsfargo.payment.kafka.PaymentEventSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        
        this.producer = new KafkaProducer<>(props);
        
        // Create producer for cancellation messages (camt.055/camt.056)
        Properties cancellationProps = new Properties();
        cancellationProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        cancellationProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cancellationProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cancellationProps.put(ProducerConfig.ACKS_CONFIG, "all");
        cancellationProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        cancellationProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        
        this.cancellationProducer = new KafkaProducer<>(cancellationProps);
    }
    
    /**
     * Parse rail-specific message and convert to PaymentEvent.
     * 
     * @param rawMessage Raw message from the rail
     * @return PaymentEvent or null if parsing fails
     */
    protected abstract PaymentEvent parseMessage(String rawMessage);
    
    /**
     * Publish PaymentEvent to orchestrator.
     */
    protected void publishToOrchestrator(PaymentEvent event) {
        String endToEndId = event.getEndToEndId();
        
        log.info("Publishing PaymentEvent to orchestrator: E2E={}, MsgId={}, Source={}", 
            endToEndId, event.getMsgId(), messageSource.getValue());
        
        try {
            ProducerRecord<String, PaymentEvent> record = new ProducerRecord<>(
                TOPIC_ORCHESTRATOR_IN, endToEndId, event);
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Failed to publish PaymentEvent to orchestrator: E2E={}", endToEndId, exception);
                } else {
                    log.info("Published PaymentEvent to orchestrator: topic={}, partition={}, offset={}, E2E={}", 
                        metadata.topic(), metadata.partition(), metadata.offset(), endToEndId);
                }
            });
        } catch (Exception e) {
            log.error("Error publishing PaymentEvent to orchestrator: E2E={}", endToEndId, e);
        }
    }
    
    /**
     * Create base PaymentEvent with common fields.
     */
    protected PaymentEvent createBasePaymentEvent(String msgId, String endToEndId, String rawMessage) {
        return PaymentEvent.builder()
            .msgId(msgId)
            .endToEndId(endToEndId)
            .sourceMessageType(messageSource)
            .sourceMessageRaw(rawMessage)
            .direction(direction)
            .status(PaymentStatus.RECEIVED)
            .createdTimestamp(Instant.now().toString())
            .lastUpdatedTimestamp(Instant.now().toString())
            .build();
    }
    
    /**
     * Check if message is a cancellation message and route accordingly.
     * 
     * @param rawMessage Raw message to check
     * @return true if message was a cancellation message and was routed
     */
    protected boolean routeCancellationMessageIfNeeded(String rawMessage) {
        CancellationMessageDetector.CancellationMessageType messageType = 
            CancellationMessageDetector.detectCancellationMessage(rawMessage);
        
        if (messageType != CancellationMessageDetector.CancellationMessageType.NONE) {
            log.info("Detected cancellation message type: {} - routing to cancellation topic", messageType);
            
            // Extract end-to-end ID for Kafka key (if available)
            String extractedKey = extractEndToEndIdFromCancellationMessage(rawMessage);
            final String key = (extractedKey == null || extractedKey.isEmpty()) ? 
                "CXL-" + System.currentTimeMillis() : extractedKey;
            final CancellationMessageDetector.CancellationMessageType finalMessageType = messageType;
            
            // Publish to cancellation topic
            ProducerRecord<String, String> record = new ProducerRecord<>(
                TOPIC_CANCELLATION_IN, key, rawMessage);
            
            cancellationProducer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Failed to publish cancellation message to cancellation topic: key={}", key, exception);
                } else {
                    log.info("Published cancellation message to cancellation topic: type={}, key={}, topic={}, partition={}, offset={}", 
                        finalMessageType, key, metadata.topic(), metadata.partition(), metadata.offset());
                }
            });
            
            return true;
        }
        
        return false;
    }
    
    /**
     * Extract end-to-end ID from cancellation message for Kafka key.
     */
    private String extractEndToEndIdFromCancellationMessage(String message) {
        // Try to extract OrgnlEndToEndId from camt.056
        String[] patterns = {"<OrgnlEndToEndId>", "<OrgnlEndToEndId ", ":OrgnlEndToEndId>"};
        for (String pattern : patterns) {
            int startIndex = message.indexOf(pattern);
            if (startIndex != -1) {
                startIndex = message.indexOf(">", startIndex) + 1;
                int endIndex = message.indexOf("</", startIndex);
                if (endIndex == -1) {
                    endIndex = message.indexOf("/>", startIndex);
                }
                if (endIndex != -1) {
                    return message.substring(startIndex, endIndex).trim();
                }
            }
        }
        return null;
    }
    
    /**
     * Close producers.
     */
    public void close() {
        if (producer != null) {
            producer.close();
        }
        if (cancellationProducer != null) {
            cancellationProducer.close();
        }
    }
}

