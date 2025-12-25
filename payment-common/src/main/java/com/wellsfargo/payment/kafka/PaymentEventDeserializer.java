package com.wellsfargo.payment.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wellsfargo.payment.canonical.PaymentEvent;
import org.apache.kafka.common.serialization.Deserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kafka deserializer for PaymentEvent using Jackson JSON deserialization.
 * 
 * This deserializer converts JSON bytes from Kafka messages to PaymentEvent objects.
 * It uses Jackson ObjectMapper configured with JSR310 module for proper date/time handling.
 * 
 * Thread-safe: ObjectMapper is thread-safe after configuration.
 */
public class PaymentEventDeserializer implements Deserializer<PaymentEvent> {
    
    private static final Logger log = LoggerFactory.getLogger(PaymentEventDeserializer.class);
    
    private final ObjectMapper objectMapper;
    
    public PaymentEventDeserializer() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }
    
    @Override
    public PaymentEvent deserialize(String topic, byte[] data) {
        if (data == null) {
            return null;
        }
        
        try {
            return objectMapper.readValue(data, PaymentEvent.class);
        } catch (Exception e) {
            log.error("Failed to deserialize PaymentEvent from topic: {}", topic, e);
            throw new RuntimeException("Failed to deserialize PaymentEvent", e);
        }
    }
    
    @Override
    public void close() {
        // ObjectMapper doesn't require cleanup
    }
}

