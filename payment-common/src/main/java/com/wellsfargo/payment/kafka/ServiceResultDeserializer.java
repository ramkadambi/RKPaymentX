package com.wellsfargo.payment.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wellsfargo.payment.canonical.ServiceResult;
import org.apache.kafka.common.serialization.Deserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kafka deserializer for ServiceResult using Jackson JSON deserialization.
 * 
 * This deserializer converts JSON bytes from Kafka messages to ServiceResult objects.
 * It uses Jackson ObjectMapper configured with JSR310 module for proper date/time handling.
 * 
 * Thread-safe: ObjectMapper is thread-safe after configuration.
 */
public class ServiceResultDeserializer implements Deserializer<ServiceResult> {
    
    private static final Logger log = LoggerFactory.getLogger(ServiceResultDeserializer.class);
    
    private final ObjectMapper objectMapper;
    
    public ServiceResultDeserializer() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }
    
    @Override
    public ServiceResult deserialize(String topic, byte[] data) {
        if (data == null) {
            return null;
        }
        
        try {
            return objectMapper.readValue(data, ServiceResult.class);
        } catch (Exception e) {
            log.error("Failed to deserialize ServiceResult from topic: {}", topic, e);
            throw new RuntimeException("Failed to deserialize ServiceResult", e);
        }
    }
    
    @Override
    public void close() {
        // ObjectMapper doesn't require cleanup
    }
}

