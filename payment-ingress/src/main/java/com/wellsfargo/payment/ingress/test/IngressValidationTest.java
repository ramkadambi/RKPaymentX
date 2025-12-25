package com.wellsfargo.payment.ingress.test;

import com.wellsfargo.payment.canonical.PaymentEvent;
import com.wellsfargo.payment.canonical.enums.PaymentDirection;
import com.wellsfargo.payment.ingress.common.Iso20022MessageType;
import com.wellsfargo.payment.ingress.common.MessageTypeDetector;
import com.wellsfargo.payment.ingress.swift.Pacs008Mapper;
import com.wellsfargo.payment.ingress.swift.Pacs009Mapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Fast Ingress Validation Test
 * 
 * Validates XML parsing and PaymentEvent mapping without Kafka.
 * This test runs much faster than the full Kafka-based test.
 */
public class IngressValidationTest {
    
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println(" Fast Ingress Validation Test");
        System.out.println(" No Kafka - Pure Logic Validation");
        System.out.println("========================================");
        System.out.println();
        
        if (args.length == 0) {
            System.err.println("Usage: java IngressValidationTest <xml-file-path>");
            System.exit(1);
        }
        
        String xmlPath = args[0];
        
        try {
            // Step 1: Read XML file
            System.out.println("Step 1: Reading XML file...");
            String xmlContent = new String(Files.readAllBytes(Paths.get(xmlPath)));
            System.out.println("  [OK] XML file read (" + xmlContent.length() + " bytes)");
            System.out.println();
            
            // Step 2: Detect message type
            System.out.println("Step 2: Detecting message type...");
            Iso20022MessageType messageType = MessageTypeDetector.detect(xmlContent);
            System.out.println("  [OK] Message type: " + messageType);
            System.out.println();
            
            // Step 3: Map to PaymentEvent
            System.out.println("Step 3: Mapping to PaymentEvent...");
            PaymentEvent paymentEvent;
            
            if (messageType == Iso20022MessageType.PACS_008) {
                paymentEvent = Pacs008Mapper.mapToPaymentEvent(xmlContent, PaymentDirection.OUTBOUND);
            } else if (messageType == Iso20022MessageType.PACS_009) {
                paymentEvent = Pacs009Mapper.mapToPaymentEvent(xmlContent, PaymentDirection.INBOUND);
            } else {
                throw new IllegalArgumentException("Unsupported message type: " + messageType);
            }
            
            System.out.println("  [OK] PaymentEvent created");
            System.out.println();
            
            // Step 4: Validate PaymentEvent
            System.out.println("Step 4: Validating PaymentEvent...");
            validatePaymentEvent(paymentEvent);
            System.out.println("  [OK] PaymentEvent validation passed");
            System.out.println();
            
            // Step 5: Print details
            System.out.println("========================================");
            System.out.println(" PaymentEvent Details");
            System.out.println("========================================");
            System.out.println();
            printPaymentEventDetails(paymentEvent);
            System.out.println();
            
            System.out.println("[SUCCESS] Ingress processing validated!");
            System.out.println();
            System.out.println("Summary:");
            System.out.println("  ✓ XML file read and parsed");
            System.out.println("  ✓ Message type detected: " + messageType);
            System.out.println("  ✓ PaymentEvent created with all required fields");
            System.out.println("  ✓ All validations passed");
            System.out.println();
            System.out.println("Test completed in < 1 second (no Kafka overhead)");
            
        } catch (Exception e) {
            System.err.println("[ERROR] Test failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void validatePaymentEvent(PaymentEvent event) {
        if (event.getEndToEndId() == null || event.getEndToEndId().isEmpty()) {
            throw new IllegalArgumentException("EndToEndId is required");
        }
        
        if (event.getMsgId() == null || event.getMsgId().isEmpty()) {
            throw new IllegalArgumentException("MsgId is required");
        }
        
        if (event.getAmount() == null) {
            throw new IllegalArgumentException("Amount is required");
        }
        
        if (event.getCurrency() == null || event.getCurrency().isEmpty()) {
            throw new IllegalArgumentException("Currency is required");
        }
        
        if (event.getSourceMessageType() == null) {
            throw new IllegalArgumentException("SourceMessageType is required");
        }
    }
    
    private static void printPaymentEventDetails(PaymentEvent event) {
        System.out.println("EndToEndId: " + event.getEndToEndId());
        System.out.println("MsgId: " + event.getMsgId());
        System.out.println("Amount: " + event.getAmount() + " " + event.getCurrency());
        System.out.println("SourceMessageType: " + event.getSourceMessageType());
        System.out.println("Status: " + event.getStatus());
        System.out.println("Direction: " + event.getDirection());
        
        if (event.getDebtorAgent() != null) {
            System.out.println("DebtorAgent: " + event.getDebtorAgent().getIdScheme() + "=" + event.getDebtorAgent().getIdValue());
        }
        
        if (event.getCreditorAgent() != null) {
            System.out.println("CreditorAgent: " + event.getCreditorAgent().getIdScheme() + "=" + event.getCreditorAgent().getIdValue());
        }
        
        
        if (event.getDebtor() != null) {
            System.out.println("Debtor Name: " + event.getDebtor().getName());
        }
        
        if (event.getCreditor() != null) {
            System.out.println("Creditor Name: " + event.getCreditor().getName());
        }
    }
}

