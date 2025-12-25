package com.wellsfargo.payment.ingress;

import com.wellsfargo.payment.canonical.PaymentEvent;
import com.wellsfargo.payment.canonical.enums.MessageSource;
import com.wellsfargo.payment.canonical.enums.PaymentDirection;
import com.wellsfargo.payment.canonical.enums.PaymentStatus;
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
    
    protected final KafkaProducer<String, PaymentEvent> producer;
    protected final MessageSource messageSource;
    protected final PaymentDirection direction;
    
    protected BaseIngressService(String bootstrapServers, MessageSource messageSource, PaymentDirection direction) {
        this.messageSource = messageSource;
        this.direction = direction;
        
        // Create producer
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
     * Close producer.
     */
    public void close() {
        if (producer != null) {
            producer.close();
        }
    }
}

