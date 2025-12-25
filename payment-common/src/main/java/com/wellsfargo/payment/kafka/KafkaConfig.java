package com.wellsfargo.payment.kafka;

import com.wellsfargo.payment.canonical.PaymentEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring Kafka configuration for payment processing.
 * 
 * This configuration provides reusable Kafka infrastructure for:
 * - Orchestrator service
 * - Satellite services (account validation, routing validation, sanctions check, balance check, payment posting)
 * - Ingress services (SWIFT, FED, CHIPS, IBT)
 * - Egress services (SWIFT, FED, CHIPS, IBT)
 * 
 * Key Features:
 * - Tuned for 400 TPS throughput
 * - Manual offset commit (for exactly-once processing guarantees)
 * - Batching enabled (linger.ms, batch.size) for efficiency
 * - JSON serialization using custom PaymentEventSerializer/Deserializer
 * - No business logic - pure infrastructure
 * - No topic-specific logic - reusable across all topics
 * 
 * Configuration Properties:
 * - kafka.bootstrap.servers: Kafka broker addresses (e.g., "localhost:9092")
 * - kafka.consumer.group-id: Consumer group ID (should be set per service)
 * - kafka.consumer.auto-offset-reset: Offset reset policy (default: "earliest")
 */
@Configuration
public class KafkaConfig {
    
    @Value("${kafka.bootstrap.servers:localhost:9092}")
    private String bootstrapServers;
    
    @Value("${kafka.consumer.group-id:payment-processing-group}")
    private String groupId;
    
    @Value("${kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;
    
    /**
     * Producer factory for PaymentEvent messages.
     * 
     * Configuration optimized for 400 TPS:
     * - Batch size: 16384 bytes (16KB) - balances throughput and latency
     * - Linger ms: 10ms - allows batching without significant latency impact
     * - Compression: snappy - good balance of speed and compression ratio
     * - Acks: 1 - leader acknowledgment (balanced durability/performance)
     * - Retries: 3 - handles transient failures
     * - Max in flight: 5 - allows pipelining while maintaining ordering
     */
    @Bean
    public ProducerFactory<String, PaymentEvent> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        
        // Basic configuration
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, PaymentEventSerializer.class);
        
        // Reliability
        props.put(ProducerConfig.ACKS_CONFIG, "1"); // Leader acknowledgment
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        
        // Batching (optimized for 400 TPS)
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384); // 16KB batch size
        props.put(ProducerConfig.LINGER_MS_CONFIG, 10); // Wait up to 10ms for batching
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432); // 32MB buffer
        
        // Compression
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        
        // Idempotence (for exactly-once semantics if needed)
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        
        return new DefaultKafkaProducerFactory<>(props);
    }
    
    /**
     * KafkaTemplate for sending PaymentEvent messages.
     * 
     * This template can be used across all services to send PaymentEvent messages
     * to any Kafka topic. No topic-specific logic is included.
     */
    @Bean
    @SuppressWarnings("null")
    public KafkaTemplate<String, PaymentEvent> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
    
    /**
     * Consumer factory for PaymentEvent messages.
     * 
     * Configuration:
     * - Manual offset commit (ENABLE_AUTO_COMMIT = false)
     * - String key deserializer, PaymentEvent value deserializer
     * - Configurable group ID and auto-offset-reset
     * - Fetch size optimized for throughput
     */
    @Bean
    public ConsumerFactory<String, PaymentEvent> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        
        // Basic configuration
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, PaymentEventDeserializer.class);
        
        // Consumer group
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        
        // Manual offset commit
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        
        // Performance tuning for 400 TPS
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1024); // Minimum 1KB per fetch
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500); // Max wait 500ms
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500); // Process up to 500 records per poll
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000); // 5 minutes max processing time
        
        // Session and heartbeat
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000); // 30 seconds
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000); // 10 seconds
        
        return new DefaultKafkaConsumerFactory<>(props);
    }
    
    /**
     * Kafka listener container factory for @KafkaListener annotations.
     * 
     * Configuration:
     * - Manual acknowledgment mode (for manual offset commits)
     * - Concurrent consumers (configurable via concurrency property)
     * - Error handling (default error handler)
     * 
     * Services should configure their own @KafkaListener methods with:
     * - @KafkaListener(topics = "...", groupId = "...")
     * - Manual acknowledgment: Acknowledgment parameter in listener method
     */
    @Bean
    @SuppressWarnings("null")
    public ConcurrentKafkaListenerContainerFactory<String, PaymentEvent> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, PaymentEvent> factory = 
            new ConcurrentKafkaListenerContainerFactory<>();
        
        factory.setConsumerFactory(consumerFactory());
        
        // Manual acknowledgment mode
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        
        // Concurrency (can be overridden per listener)
        factory.setConcurrency(1);
        
        return factory;
    }
}

