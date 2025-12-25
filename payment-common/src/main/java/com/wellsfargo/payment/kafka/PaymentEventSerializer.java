package com.wellsfargo.payment.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wellsfargo.payment.canonical.PaymentEvent;
import org.apache.kafka.common.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kafka serializer for PaymentEvent using Jackson JSON serialization.
 * 
 * This serializer converts PaymentEvent objects to JSON bytes for Kafka message transmission.
 * It uses Jackson ObjectMapper configured with JSR310 module for proper date/time handling.
 * 
 * Thread-safe: ObjectMapper is thread-safe after configuration.
 */
public class PaymentEventSerializer implements Serializer<PaymentEvent> {
    
    private static final Logger log = LoggerFactory.getLogger(PaymentEventSerializer.class);
    
    private final ObjectMapper objectMapper;
    
    public PaymentEventSerializer() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }
    
    @Override
    public byte[] serialize(String topic, PaymentEvent data) {
        if (data == null) {
            return null;
        }
        
        try {
            return objectMapper.writeValueAsBytes(data);
        } catch (Exception e) {
            log.error("Failed to serialize PaymentEvent for topic: {}", topic, e);
            throw new RuntimeException("Failed to serialize PaymentEvent", e);
        }
    }
    
    @Override
    public void close() {
        // ObjectMapper doesn't require cleanup
    }
}

