package com.wellsfargo.payment.test;

import com.wellsfargo.payment.canonical.PaymentEvent;
import com.wellsfargo.payment.canonical.ServiceResult;
import com.wellsfargo.payment.canonical.enums.PaymentStatus;
import com.wellsfargo.payment.canonical.enums.ServiceResultStatus;
import com.wellsfargo.payment.ingress.common.Iso20022MessageType;
import com.wellsfargo.payment.ingress.common.MessageTypeDetector;
import com.wellsfargo.payment.ingress.swift.Pacs008Mapper;
import com.wellsfargo.payment.canonical.enums.PaymentDirection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock Kafka Test - In-Memory Payment Flow Test
 * 
 * Simulates Kafka topics using in-memory queues to test the payment flow
 * without the overhead of real Kafka.
 */
public class MockPaymentFlowTest {
    
    // In-memory message queues (simulating Kafka topics)
    private static final Map<String, BlockingQueue<Object>> topics = new ConcurrentHashMap<>();
    
    // Track message counts per topic
    private static final Map<String, AtomicInteger> messageCounts = new ConcurrentHashMap<>();
    
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println(" Mock Kafka Payment Flow Test");
        System.out.println("========================================");
        System.out.println();
        
        try {
            // Initialize topics
            initializeTopics();
            
            // Step 1: Ingress - Read and parse XML
            System.out.println("Step 1: Ingress - Processing PACS.008 XML...");
            String xmlPath = args.length > 0 ? args[0] : "../test-data/pacs008_wells_final.xml";
            PaymentEvent paymentEvent = processIngress(xmlPath);
            System.out.println("  [OK] PaymentEvent created: " + paymentEvent.getEndToEndId());
            System.out.println("        Amount: " + paymentEvent.getAmount() + " " + paymentEvent.getCurrency());
            System.out.println();
            
            // Step 2: Publish to orchestrator.in
            System.out.println("Step 2: Publishing to payments.orchestrator.in...");
            publish("payments.orchestrator.in", paymentEvent.getEndToEndId(), paymentEvent);
            System.out.println("  [OK] Published to orchestrator");
            System.out.println();
            
            // Step 3: Orchestrator - Route to account validation
            System.out.println("Step 3: Orchestrator - Routing to account validation...");
            PaymentEvent accountValidationEvent = consume("payments.orchestrator.in");
            publish("payments.step.account_validation", accountValidationEvent.getEndToEndId(), accountValidationEvent);
            System.out.println("  [OK] Routed to account validation");
            System.out.println();
            
            // Step 4: Account Validation - Mock service
            System.out.println("Step 4: Account Validation - Processing...");
            PaymentEvent accountValidationInput = consume("payments.step.account_validation");
            PaymentEvent enrichedEvent = enrichAccountInfo(accountValidationInput);
            ServiceResult accountResult = ServiceResult.builder()
                .endToEndId(enrichedEvent.getEndToEndId())
                .serviceName("account_validation")
                .status(ServiceResultStatus.PASS)
                .processingTimestamp(java.time.Instant.now().toString())
                .build();
            publish("service.results.account_validation", enrichedEvent.getEndToEndId(), accountResult);
            publish("payments.step.routing_validation", enrichedEvent.getEndToEndId(), enrichedEvent);
            System.out.println("  [OK] Account validated and enriched");
            System.out.println();
            
            // Step 5: Routing Validation - Mock service
            System.out.println("Step 5: Routing Validation - Processing...");
            PaymentEvent routingInput = consume("payments.step.routing_validation");
            PaymentEvent routedEvent = applyRouting(routingInput);
            ServiceResult routingResult = ServiceResult.builder()
                .endToEndId(routedEvent.getEndToEndId())
                .serviceName("routing_validation")
                .status(ServiceResultStatus.PASS)
                .processingTimestamp(java.time.Instant.now().toString())
                .build();
            publish("service.results.routing_validation", routedEvent.getEndToEndId(), routingResult);
            publish("payments.step.sanctions_check", routedEvent.getEndToEndId(), routedEvent);
            System.out.println("  [OK] Routing determined: IBT");
            System.out.println();
            
            // Step 6: Sanctions Check - Mock service
            System.out.println("Step 6: Sanctions Check - Processing...");
            PaymentEvent sanctionsInput = consume("payments.step.sanctions_check");
            ServiceResult sanctionsResult = performSanctionsCheck(sanctionsInput);
            publish("service.results.sanctions_check", sanctionsInput.getEndToEndId(), sanctionsResult);
            if (sanctionsResult.getStatus() == ServiceResultStatus.PASS) {
                publish("payments.step.balance_check", sanctionsInput.getEndToEndId(), sanctionsInput);
            }
            System.out.println("  [OK] Sanctions check: " + sanctionsResult.getStatus());
            System.out.println();
            
            // Step 7: Balance Check - Mock service
            System.out.println("Step 7: Balance Check - Processing...");
            PaymentEvent balanceInput = consume("payments.step.balance_check");
            ServiceResult balanceResult = performBalanceCheck(balanceInput);
            publish("service.results.balance_check", balanceInput.getEndToEndId(), balanceResult);
            if (balanceResult.getStatus() == ServiceResultStatus.PASS) {
                publish("payments.step.payment_posting", balanceInput.getEndToEndId(), balanceInput);
            }
            System.out.println("  [OK] Balance check: " + balanceResult.getStatus());
            System.out.println();
            
            // Step 8: Payment Posting - Mock service
            System.out.println("Step 8: Payment Posting - Processing...");
            PaymentEvent postingInput = consume("payments.step.payment_posting");
            ServiceResult postingResult = performPaymentPosting(postingInput);
            publish("service.results.payment_posting", postingInput.getEndToEndId(), postingResult);
            System.out.println("  [OK] Payment posted: " + postingResult.getStatus());
            System.out.println();
            
            // Step 9: Final Status
            System.out.println("Step 9: Publishing Final Status...");
            PaymentEvent finalEvent = postingInput.toBuilder()
                .status(PaymentStatus.COMPLETED)
                .build();
            publish("payments.final.status", finalEvent.getEndToEndId(), finalEvent);
            System.out.println("  [OK] Final status published");
            System.out.println();
            
            // Summary
            System.out.println("========================================");
            System.out.println(" Test Summary");
            System.out.println("========================================");
            System.out.println();
            printMessageCounts();
            System.out.println();
            System.out.println("[SUCCESS] All stages completed!");
            
        } catch (Exception e) {
            System.err.println("[ERROR] Test failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void initializeTopics() {
        String[] topicNames = {
            "payments.orchestrator.in",
            "payments.step.account_validation",
            "service.results.account_validation",
            "payments.step.routing_validation",
            "service.results.routing_validation",
            "payments.step.sanctions_check",
            "service.results.sanctions_check",
            "payments.step.balance_check",
            "service.results.balance_check",
            "payments.step.payment_posting",
            "service.results.payment_posting",
            "payments.final.status"
        };
        
        for (String topic : topicNames) {
            topics.put(topic, new LinkedBlockingQueue<>());
            messageCounts.put(topic, new AtomicInteger(0));
        }
    }
    
    private static PaymentEvent processIngress(String xmlPath) throws Exception {
        String xmlContent = new String(Files.readAllBytes(Paths.get(xmlPath)));
        Iso20022MessageType messageType = MessageTypeDetector.detect(xmlContent);
        if (messageType == Iso20022MessageType.PACS_008) {
            return Pacs008Mapper.mapToPaymentEvent(xmlContent, PaymentDirection.OUTBOUND);
        } else {
            throw new IllegalArgumentException("Unsupported message type: " + messageType);
        }
    }
    
    private static void publish(String topic, String key, Object message) {
        topics.get(topic).offer(message);
        messageCounts.get(topic).incrementAndGet();
    }
    
    @SuppressWarnings("unchecked")
    private static <T> T consume(String topic) throws InterruptedException {
        return (T) topics.get(topic).take();
    }
    
    private static PaymentEvent enrichAccountInfo(PaymentEvent event) {
        // Mock account enrichment - return as-is for now
        return event;
    }
    
    private static PaymentEvent applyRouting(PaymentEvent event) {
        // Mock routing - return as-is for now
        return event;
    }
    
    private static ServiceResult performSanctionsCheck(PaymentEvent event) {
        // Mock sanctions check - always pass for test
        return ServiceResult.builder()
            .endToEndId(event.getEndToEndId())
            .serviceName("sanctions_check")
            .status(ServiceResultStatus.PASS)
            .processingTimestamp(java.time.Instant.now().toString())
            .build();
    }
    
    private static ServiceResult performBalanceCheck(PaymentEvent event) {
        // Mock balance check - always pass for test
        return ServiceResult.builder()
            .endToEndId(event.getEndToEndId())
            .serviceName("balance_check")
            .status(ServiceResultStatus.PASS)
            .processingTimestamp(java.time.Instant.now().toString())
            .build();
    }
    
    private static ServiceResult performPaymentPosting(PaymentEvent event) {
        // Mock payment posting - always pass for test
        return ServiceResult.builder()
            .endToEndId(event.getEndToEndId())
            .serviceName("payment_posting")
            .status(ServiceResultStatus.PASS)
            .processingTimestamp(java.time.Instant.now().toString())
            .build();
    }
    
    private static void printMessageCounts() {
        System.out.println("Message counts per topic:");
        messageCounts.forEach((topic, count) -> {
            System.out.println("  " + topic + ": " + count.get() + " messages");
        });
    }
}

