package com.wellsfargo.payment.ingress.api;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Future;

/**
 * REST API Controller for Wells Ingress.
 * 
 * Provides HTTP endpoints for internal applications to submit payments:
 * - Branch applications
 * - Online banking portal
 * - Scheduled payment systems (standing orders)
 * 
 * Accepts PACS.008 XML or JSON payment requests and publishes to Kafka topic.
 */
@RestController
@RequestMapping("/api/v1/wells/payments")
@CrossOrigin(origins = "*") // Allow CORS for internal applications
public class WellsIngressController {
    
    private static final Logger log = LoggerFactory.getLogger(WellsIngressController.class);
    private static final String TOPIC_WELLS_IN = "ingress.wells.in";
    
    @Value("${kafka.bootstrap.servers:localhost:9092}")
    private String bootstrapServers;
    
    private KafkaProducer<String, String> kafkaProducer;
    
    @PostConstruct
    public void init() {
        log.info("Initializing Wells Ingress API Controller");
        
        // Create Kafka producer for publishing to ingress.wells.in
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all"); // Wait for all replicas
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        
        kafkaProducer = new KafkaProducer<>(props);
        
        log.info("Wells Ingress API Controller initialized");
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down Wells Ingress API Controller");
        if (kafkaProducer != null) {
            kafkaProducer.close();
        }
    }
    
    /**
     * Submit payment via PACS.008 XML.
     * 
     * Used by: Branch applications, Online portal, Scheduled payment systems
     * 
     * @param pacs008Xml PACS.008 XML message
     * @param channel Channel identifier (e.g., "BRANCH", "ONLINE", "SCHEDULED")
     * @return Payment submission response
     */
    @PostMapping(value = "/submit", consumes = MediaType.APPLICATION_XML_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> submitPaymentXml(
            @RequestBody String pacs008Xml,
            @RequestHeader(value = "X-Channel", required = false, defaultValue = "API") String channel) {
        
        log.info("Received payment submission request - Channel: {}, Message length: {}", channel, pacs008Xml.length());
        
        try {
            // Validate XML is not empty
            if (pacs008Xml == null || pacs008Xml.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(createErrorResponse("INVALID_REQUEST", "PACS.008 XML message cannot be empty"));
            }
            
            // Extract endToEndId for Kafka key (for idempotency and partitioning)
            String endToEndId = extractEndToEndId(pacs008Xml);
            if (endToEndId == null || endToEndId.isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(createErrorResponse("INVALID_REQUEST", "PACS.008 message must contain EndToEndId"));
            }
            
            // Validate PACS.008 structure (basic validation)
            if (!pacs008Xml.contains("FIToFICstmrCdtTrf") && !pacs008Xml.contains("pacs.008")) {
                return ResponseEntity.badRequest()
                    .body(createErrorResponse("INVALID_FORMAT", "Message does not appear to be a valid PACS.008"));
            }
            
            // Publish to Kafka topic
            ProducerRecord<String, String> record = new ProducerRecord<>(
                TOPIC_WELLS_IN, 
                endToEndId, 
                pacs008Xml
            );
            
            // Add channel as header for tracking
            record.headers().add("channel", channel.getBytes());
            
            Future<RecordMetadata> future = kafkaProducer.send(record);
            RecordMetadata metadata = future.get(); // Wait for confirmation
            
            log.info("Payment submitted successfully - EndToEndId: {}, Channel: {}, Topic: {}, Partition: {}, Offset: {}", 
                endToEndId, channel, metadata.topic(), metadata.partition(), metadata.offset());
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "ACCEPTED");
            response.put("message", "Payment submitted successfully");
            response.put("endToEndId", endToEndId);
            response.put("channel", channel);
            response.put("topic", metadata.topic());
            response.put("partition", metadata.partition());
            response.put("offset", metadata.offset());
            response.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.accepted().body(response);
            
        } catch (Exception e) {
            log.error("Error submitting payment - Channel: {}", channel, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("SUBMISSION_ERROR", "Failed to submit payment: " + e.getMessage()));
        }
    }
    
    /**
     * Submit payment via JSON (simplified format for internal applications).
     * 
     * Used by: Branch applications, Online portal, Scheduled payment systems
     * 
     * @param request JSON payment request
     * @param channel Channel identifier (e.g., "BRANCH", "ONLINE", "SCHEDULED")
     * @return Payment submission response
     */
    @PostMapping(value = "/submit", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> submitPaymentJson(
            @RequestBody PaymentRequest request,
            @RequestHeader(value = "X-Channel", required = false, defaultValue = "API") String channel) {
        
        log.info("Received payment submission request (JSON) - Channel: {}, EndToEndId: {}", 
            channel, request.getEndToEndId());
        
        try {
            // Validate request
            String validationError = validatePaymentRequest(request);
            if (validationError != null) {
                return ResponseEntity.badRequest()
                    .body(createErrorResponse("VALIDATION_ERROR", validationError));
            }
            
            // Convert JSON to PACS.008 XML (simplified conversion)
            String pacs008Xml = convertToPacs008(request);
            
            // Publish to Kafka topic
            ProducerRecord<String, String> record = new ProducerRecord<>(
                TOPIC_WELLS_IN, 
                request.getEndToEndId(), 
                pacs008Xml
            );
            
            // Add channel as header for tracking
            record.headers().add("channel", channel.getBytes());
            
            Future<RecordMetadata> future = kafkaProducer.send(record);
            RecordMetadata metadata = future.get(); // Wait for confirmation
            
            log.info("Payment submitted successfully (JSON) - EndToEndId: {}, Channel: {}, Topic: {}, Partition: {}", 
                request.getEndToEndId(), channel, metadata.topic(), metadata.partition());
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "ACCEPTED");
            response.put("message", "Payment submitted successfully");
            response.put("endToEndId", request.getEndToEndId());
            response.put("channel", channel);
            response.put("topic", metadata.topic());
            response.put("partition", metadata.partition());
            response.put("offset", metadata.offset());
            response.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.accepted().body(response);
            
        } catch (Exception e) {
            log.error("Error submitting payment (JSON) - Channel: {}, EndToEndId: {}", 
                channel, request != null ? request.getEndToEndId() : "N/A", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("SUBMISSION_ERROR", "Failed to submit payment: " + e.getMessage()));
        }
    }
    
    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "Wells Ingress API");
        return ResponseEntity.ok(response);
    }
    
    /**
     * Extract EndToEndId from PACS.008 XML.
     */
    private String extractEndToEndId(String xml) {
        try {
            // Simple extraction - look for EndToEndId element
            int startIdx = xml.indexOf("<EndToEndId>");
            if (startIdx == -1) {
                startIdx = xml.indexOf("<EndToEndId ");
            }
            if (startIdx == -1) {
                return null;
            }
            
            startIdx = xml.indexOf(">", startIdx) + 1;
            int endIdx = xml.indexOf("</EndToEndId>", startIdx);
            if (endIdx == -1) {
                endIdx = xml.indexOf("/>", startIdx);
                if (endIdx == -1) {
                    return null;
                }
            }
            
            return xml.substring(startIdx, endIdx).trim();
        } catch (Exception e) {
            log.warn("Failed to extract EndToEndId from XML", e);
            return null;
        }
    }
    
    /**
     * Validate payment request.
     */
    private String validatePaymentRequest(PaymentRequest request) {
        if (request == null) {
            return "Payment request cannot be null";
        }
        if (request.getEndToEndId() == null || request.getEndToEndId().trim().isEmpty()) {
            return "EndToEndId is required";
        }
        if (request.getAmount() == null || request.getAmount().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            return "Amount must be greater than zero";
        }
        if (request.getCurrency() == null || request.getCurrency().trim().isEmpty()) {
            return "Currency is required";
        }
        if (request.getDebtorAccount() == null || request.getDebtorAccount().trim().isEmpty()) {
            return "Debtor account is required";
        }
        if (request.getCreditorAccount() == null || request.getCreditorAccount().trim().isEmpty()) {
            return "Creditor account is required";
        }
        return null;
    }
    
    /**
     * Convert JSON PaymentRequest to PACS.008 XML.
     * This is a simplified conversion for internal applications.
     */
    private String convertToPacs008(PaymentRequest request) {
        // Generate message ID if not provided
        String msgId = request.getMsgId() != null ? request.getMsgId() : 
            "MSG-" + System.currentTimeMillis() + "-" + request.getEndToEndId().substring(0, Math.min(8, request.getEndToEndId().length()));
        
        // Build PACS.008 XML
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.008.001.12\">\n");
        xml.append("  <FIToFICstmrCdtTrf>\n");
        xml.append("    <GrpHdr>\n");
        xml.append("      <MsgId>").append(msgId).append("</MsgId>\n");
        xml.append("      <CreDtTm>").append(java.time.Instant.now().toString()).append("</CreDtTm>\n");
        xml.append("      <NbOfTxs>1</NbOfTxs>\n");
        xml.append("    </GrpHdr>\n");
        xml.append("    <CdtTrfTxInf>\n");
        xml.append("      <PmtId>\n");
        xml.append("        <EndToEndId>").append(request.getEndToEndId()).append("</EndToEndId>\n");
        xml.append("      </PmtId>\n");
        xml.append("      <IntrBkSttlmAmt Ccy=\"").append(request.getCurrency()).append("\">")
           .append(request.getAmount()).append("</IntrBkSttlmAmt>\n");
        xml.append("      <DbtrAgt>\n");
        xml.append("        <FinInstnId>\n");
        xml.append("          <BICFI>WFBIUS6SXXX</BICFI>\n"); // Wells Fargo (debtor)
        xml.append("        </FinInstnId>\n");
        xml.append("      </DbtrAgt>\n");
        xml.append("      <Dbtr>\n");
        xml.append("        <Nm>").append(escapeXml(request.getDebtorName() != null ? request.getDebtorName() : "Wells Fargo Customer")).append("</Nm>\n");
        xml.append("      </Dbtr>\n");
        xml.append("      <DbtrAcct>\n");
        xml.append("        <Id>\n");
        xml.append("          <Othr>\n");
        xml.append("            <Id>").append(request.getDebtorAccount()).append("</Id>\n");
        xml.append("          </Othr>\n");
        xml.append("        </Id>\n");
        xml.append("      </DbtrAcct>\n");
        xml.append("      <CdtrAgt>\n");
        xml.append("        <FinInstnId>\n");
        if (request.getCreditorBic() != null && !request.getCreditorBic().trim().isEmpty()) {
            xml.append("          <BICFI>").append(request.getCreditorBic()).append("</BICFI>\n");
        } else {
            // Will be determined by routing logic
            xml.append("          <BICFI>UNKNOWN</BICFI>\n");
        }
        xml.append("        </FinInstnId>\n");
        xml.append("      </CdtrAgt>\n");
        xml.append("      <Cdtr>\n");
        xml.append("        <Nm>").append(escapeXml(request.getCreditorName() != null ? request.getCreditorName() : "Creditor")).append("</Nm>\n");
        xml.append("      </Cdtr>\n");
        xml.append("      <CdtrAcct>\n");
        xml.append("        <Id>\n");
        xml.append("          <Othr>\n");
        xml.append("            <Id>").append(request.getCreditorAccount()).append("</Id>\n");
        xml.append("          </Othr>\n");
        xml.append("        </Id>\n");
        xml.append("      </CdtrAcct>\n");
        if (request.getRemittanceInfo() != null && !request.getRemittanceInfo().trim().isEmpty()) {
            xml.append("      <RmtInf>\n");
            xml.append("        <Ustrd>").append(escapeXml(request.getRemittanceInfo())).append("</Ustrd>\n");
            xml.append("      </RmtInf>\n");
        }
        xml.append("    </CdtTrfTxInf>\n");
        xml.append("  </FIToFICstmrCdtTrf>\n");
        xml.append("</Document>");
        
        return xml.toString();
    }
    
    /**
     * Escape XML special characters.
     */
    private String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&apos;");
    }
    
    /**
     * Create error response.
     */
    private Map<String, Object> createErrorResponse(String errorCode, String errorMessage) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "ERROR");
        response.put("errorCode", errorCode);
        response.put("errorMessage", errorMessage);
        response.put("timestamp", System.currentTimeMillis());
        return response;
    }
    
    /**
     * Payment Request DTO for JSON submissions.
     */
    public static class PaymentRequest {
        private String msgId;
        private String endToEndId;
        private java.math.BigDecimal amount;
        private String currency;
        private String debtorAccount;
        private String debtorName;
        private String creditorAccount;
        private String creditorName;
        private String creditorBic; // Optional - will be determined by routing if not provided
        private String remittanceInfo;
        
        // Getters and setters
        public String getMsgId() { return msgId; }
        public void setMsgId(String msgId) { this.msgId = msgId; }
        
        public String getEndToEndId() { return endToEndId; }
        public void setEndToEndId(String endToEndId) { this.endToEndId = endToEndId; }
        
        public java.math.BigDecimal getAmount() { return amount; }
        public void setAmount(java.math.BigDecimal amount) { this.amount = amount; }
        
        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }
        
        public String getDebtorAccount() { return debtorAccount; }
        public void setDebtorAccount(String debtorAccount) { this.debtorAccount = debtorAccount; }
        
        public String getDebtorName() { return debtorName; }
        public void setDebtorName(String debtorName) { this.debtorName = debtorName; }
        
        public String getCreditorAccount() { return creditorAccount; }
        public void setCreditorAccount(String creditorAccount) { this.creditorAccount = creditorAccount; }
        
        public String getCreditorName() { return creditorName; }
        public void setCreditorName(String creditorName) { this.creditorName = creditorName; }
        
        public String getCreditorBic() { return creditorBic; }
        public void setCreditorBic(String creditorBic) { this.creditorBic = creditorBic; }
        
        public String getRemittanceInfo() { return remittanceInfo; }
        public void setRemittanceInfo(String remittanceInfo) { this.remittanceInfo = remittanceInfo; }
    }
}

