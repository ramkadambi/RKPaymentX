package com.wellsfargo.payment.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wellsfargo.payment.canonical.ServiceResult;
import org.apache.kafka.common.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kafka serializer for ServiceResult using Jackson JSON serialization.
 * 
 * This serializer converts ServiceResult objects to JSON bytes for Kafka message transmission.
 * It uses Jackson ObjectMapper configured with JSR310 module for proper date/time handling.
 * 
 * Thread-safe: ObjectMapper is thread-safe after configuration.
 */
public class ServiceResultSerializer implements Serializer<ServiceResult> {
    
    private static final Logger log = LoggerFactory.getLogger(ServiceResultSerializer.class);
    
    private final ObjectMapper objectMapper;
    
    public ServiceResultSerializer() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }
    
    @Override
    public byte[] serialize(String topic, ServiceResult data) {
        if (data == null) {
            return null;
        }
        
        try {
            return objectMapper.writeValueAsBytes(data);
        } catch (Exception e) {
            log.error("Failed to serialize ServiceResult for topic: {}", topic, e);
            throw new RuntimeException("Failed to serialize ServiceResult", e);
        }
    }
    
    @Override
    public void close() {
        // ObjectMapper doesn't require cleanup
    }
}

