package com.wellsfargo.payment.egress;

import com.wellsfargo.payment.canonical.PaymentEvent;
import com.wellsfargo.payment.canonical.enums.RoutingNetwork;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Base class for egress services.
 * 
 * Provides common functionality for generating outbound messages
 * from PaymentEvent and publishing to rail-specific topics.
 */
public abstract class BaseEgressService {
    
    protected static final Logger log = LoggerFactory.getLogger(BaseEgressService.class);
    
    protected final KafkaProducer<String, String> producer;
    protected final RoutingNetwork targetNetwork;
    protected final String outputTopic;
    
    protected BaseEgressService(String bootstrapServers, RoutingNetwork targetNetwork, String outputTopic) {
        this.targetNetwork = targetNetwork;
        this.outputTopic = outputTopic;
        
        // Create producer
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        
        this.producer = new KafkaProducer<>(props);
    }
    
    /**
     * Generate outbound message from PaymentEvent.
     * 
     * @param event PaymentEvent to convert
     * @return Outbound message string (XML/JSON format)
     */
    protected abstract String generateMessage(PaymentEvent event);
    
    /**
     * Check if this egress service should handle the PaymentEvent.
     * 
     * @param event PaymentEvent to check
     * @return True if this service should handle it, False otherwise
     */
    protected boolean shouldHandle(PaymentEvent event) {
        if (event.getRoutingContext() == null || event.getRoutingContext().getSelectedNetwork() == null) {
            return false;
        }
        return event.getRoutingContext().getSelectedNetwork() == targetNetwork;
    }
    
    /**
     * Publish outbound message to rail-specific topic.
     */
    protected void publishMessage(PaymentEvent event, String message) {
        String endToEndId = event.getEndToEndId();
        
        log.info("Publishing {} message to topic={}, E2E={}", 
            targetNetwork.getValue(), outputTopic, endToEndId);
        
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                outputTopic, endToEndId, message);
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Failed to publish {} message to topic={}, E2E={}", 
                        targetNetwork.getValue(), outputTopic, endToEndId, exception);
                } else {
                    log.info("Published {} message to topic={}, partition={}, offset={}, E2E={}", 
                        targetNetwork.getValue(), metadata.topic(), metadata.partition(), 
                        metadata.offset(), endToEndId);
                }
            });
        } catch (Exception e) {
            log.error("Error publishing {} message to topic={}, E2E={}", 
                targetNetwork.getValue(), outputTopic, endToEndId, e);
        }
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

