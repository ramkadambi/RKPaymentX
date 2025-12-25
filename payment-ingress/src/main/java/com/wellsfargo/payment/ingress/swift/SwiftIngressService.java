package com.wellsfargo.payment.ingress.swift;

import com.wellsfargo.payment.canonical.PaymentEvent;
import com.wellsfargo.payment.canonical.enums.PaymentDirection;
import com.wellsfargo.payment.ingress.common.Iso20022MessageType;
import com.wellsfargo.payment.ingress.common.MessageTypeDetector;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * SWIFT Ingress Service - CLI-based file ingestion.
 * 
 * Entry point for processing SWIFT PACS.008 and PACS.009 messages from files.
 * 
 * Features:
 * - Accepts XML files via command line (--input-file=/path/to/xml)
 * - Detects message type (PACS.008 or PACS.009) using MessageTypeDetector
 * - Converts XML to canonical PaymentEvent using appropriate mapper
 * - Publishes PaymentEvent to Kafka topic: payments.orchestrator.in
 * - Logs key identifiers (endToEndId, message type)
 * 
 * Usage:
 * <pre>{@code
 * java -cp ... SwiftIngressService --input-file=/path/to/pacs.008.xml
 * java -cp ... SwiftIngressService --input-file=/path/to/pacs.009.xml
 * }</pre>
 * 
 * This service performs NO routing logic and NO validation logic.
 * It fails fast on invalid XML or mapping errors.
 */
public class SwiftIngressService {
    
    private static final Logger log = LoggerFactory.getLogger(SwiftIngressService.class);
    private static final String TOPIC_ORCHESTRATOR_IN = "payments.orchestrator.in";
    private static final String DEFAULT_KAFKA_BOOTSTRAP_SERVERS = "localhost:9092";
    
    private final KafkaProducer<String, PaymentEvent> producer;
    
    public SwiftIngressService(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, 
            com.wellsfargo.payment.kafka.PaymentEventSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all"); // Required for idempotent producer
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        
        this.producer = new KafkaProducer<>(props);
    }
    
    /**
     * Main entry point for CLI execution.
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }
        
        String inputFile = null;
        String bootstrapServers = DEFAULT_KAFKA_BOOTSTRAP_SERVERS;
        
        // Parse command line arguments
        for (String arg : args) {
            if (arg.startsWith("--input-file=")) {
                inputFile = arg.substring("--input-file=".length());
            } else if (arg.startsWith("--kafka-bootstrap-servers=")) {
                bootstrapServers = arg.substring("--kafka-bootstrap-servers=".length());
            } else if (arg.equals("--help") || arg.equals("-h")) {
                printUsage();
                System.exit(0);
            } else {
                log.error("Unknown argument: {}", arg);
                printUsage();
                System.exit(1);
            }
        }
        
        if (inputFile == null || inputFile.isEmpty()) {
            log.error("--input-file parameter is required");
            printUsage();
            System.exit(1);
        }
        
        // Process the file
        SwiftIngressService service = new SwiftIngressService(bootstrapServers);
        try {
            service.processFile(inputFile);
            log.info("Successfully processed file: {}", inputFile);
            System.exit(0);
        } catch (Exception e) {
            log.error("Failed to process file: {}", inputFile, e);
            System.exit(1);
        } finally {
            service.close();
        }
    }
    
    /**
     * Process a single XML file.
     * 
     * @param filePath Path to the XML file
     * @throws IOException if file cannot be read
     * @throws MessageTypeDetector.MessageTypeDetectionException if message type cannot be detected
     * @throws Pacs008Mapper.Pacs008MappingException if PACS.008 mapping fails
     * @throws Pacs009Mapper.Pacs009MappingException if PACS.009 mapping fails
     */
    public void processFile(String filePath) 
            throws IOException, 
                   MessageTypeDetector.MessageTypeDetectionException,
                   Pacs008Mapper.Pacs008MappingException,
                   Pacs009Mapper.Pacs009MappingException {
        
        log.info("Processing file: {}", filePath);
        
        // Read XML from disk
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new IOException("File does not exist: " + filePath);
        }
        
        if (!Files.isRegularFile(path)) {
            throw new IOException("Path is not a regular file: " + filePath);
        }
        
        String xmlContent = Files.readString(path);
        if (xmlContent == null || xmlContent.trim().isEmpty()) {
            throw new IOException("File is empty: " + filePath);
        }
        
        log.debug("Read {} bytes from file: {}", xmlContent.length(), filePath);
        
        // Detect message type
        Iso20022MessageType messageType;
        try {
            messageType = MessageTypeDetector.detect(xmlContent);
            log.info("Detected message type: {}", messageType);
        } catch (MessageTypeDetector.MessageTypeDetectionException e) {
            log.error("Failed to detect message type from file: {}", filePath, e);
            throw e;
        }
        
        // Convert XML to canonical PaymentEvent
        PaymentEvent paymentEvent;
        try {
            if (messageType == Iso20022MessageType.PACS_008) {
                paymentEvent = Pacs008Mapper.mapToPaymentEvent(xmlContent, PaymentDirection.INBOUND);
                log.info("Mapped PACS.008 to PaymentEvent: endToEndId={}, msgId={}", 
                    paymentEvent.getEndToEndId(), paymentEvent.getMsgId());
            } else if (messageType == Iso20022MessageType.PACS_009) {
                paymentEvent = Pacs009Mapper.mapToPaymentEvent(xmlContent, PaymentDirection.INBOUND);
                log.info("Mapped PACS.009 to PaymentEvent: endToEndId={}, msgId={}", 
                    paymentEvent.getEndToEndId(), paymentEvent.getMsgId());
            } else {
                throw new IllegalStateException("Unsupported message type: " + messageType);
            }
        } catch (Pacs008Mapper.Pacs008MappingException e) {
            log.error("Failed to map PACS.008 message from file: {}", filePath, e);
            throw e;
        } catch (Pacs009Mapper.Pacs009MappingException e) {
            log.error("Failed to map PACS.009 message from file: {}", filePath, e);
            throw e;
        }
        
        // Log key identifiers
        log.info("PaymentEvent created - MessageType: {}, EndToEndId: {}, MsgId: {}, Amount: {} {}", 
            messageType, 
            paymentEvent.getEndToEndId(), 
            paymentEvent.getMsgId(),
            paymentEvent.getAmount(),
            paymentEvent.getCurrency());
        
        // Publish PaymentEvent to Kafka
        publishToOrchestrator(paymentEvent, messageType);
    }
    
    /**
     * Publish PaymentEvent to orchestrator Kafka topic.
     */
    private void publishToOrchestrator(PaymentEvent event, Iso20022MessageType messageType) {
        String endToEndId = event.getEndToEndId();
        String messageTypeStr = messageType == Iso20022MessageType.PACS_008 ? "PACS_008" : "PACS_009";
        
        // Structured logging: endToEndId, message type, current stage
        log.info("Publishing PaymentEvent to orchestrator - endToEndId={}, messageType={}, stage=INGRESS_PUBLISH", 
            endToEndId, messageTypeStr);
        
        try {
            ProducerRecord<String, PaymentEvent> record = new ProducerRecord<>(
                TOPIC_ORCHESTRATOR_IN, endToEndId, event);
            
            // Send synchronously to ensure message is published before exiting
            producer.send(record).get(30, TimeUnit.SECONDS);
            
            // Structured logging: confirmation of successful publish
            log.info("Successfully published PaymentEvent to orchestrator - endToEndId={}, messageType={}, stage=INGRESS_PUBLISH", 
                endToEndId, messageTypeStr);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while publishing PaymentEvent to orchestrator - endToEndId={}, messageType={}, stage=INGRESS_PUBLISH", 
                endToEndId, messageTypeStr, e);
            throw new RuntimeException("Interrupted while publishing PaymentEvent to Kafka", e);
        } catch (ExecutionException e) {
            log.error("Failed to publish PaymentEvent to orchestrator - endToEndId={}, messageType={}, stage=INGRESS_PUBLISH", 
                endToEndId, messageTypeStr, e);
            throw new RuntimeException("Failed to publish PaymentEvent to Kafka", e);
        } catch (TimeoutException e) {
            log.error("Timeout while publishing PaymentEvent to orchestrator - endToEndId={}, messageType={}, stage=INGRESS_PUBLISH", 
                endToEndId, messageTypeStr, e);
            throw new RuntimeException("Timeout while publishing PaymentEvent to Kafka", e);
        }
    }
    
    /**
     * Close Kafka producer.
     */
    public void close() {
        if (producer != null) {
            producer.close();
            log.info("Kafka producer closed");
        }
    }
    
    /**
     * Print usage information.
     */
    private static void printUsage() {
        System.out.println("SWIFT Ingress Service - CLI File Ingestion");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java SwiftIngressService --input-file=<path> [options]");
        System.out.println();
        System.out.println("Required:");
        System.out.println("  --input-file=<path>    Path to PACS.008 or PACS.009 XML file");
        System.out.println();
        System.out.println("Optional:");
        System.out.println("  --kafka-bootstrap-servers=<servers>  Kafka bootstrap servers (default: localhost:9092)");
        System.out.println("  --help, -h                           Show this help message");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java SwiftIngressService --input-file=/path/to/pacs.008.xml");
        System.out.println("  java SwiftIngressService --input-file=/path/to/pacs.009.xml --kafka-bootstrap-servers=localhost:9092");
    }
}

