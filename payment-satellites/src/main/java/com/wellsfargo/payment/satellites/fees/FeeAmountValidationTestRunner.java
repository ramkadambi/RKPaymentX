package com.wellsfargo.payment.satellites.fees;

import com.wellsfargo.payment.canonical.*;
import com.wellsfargo.payment.canonical.enums.MessageSource;
import com.wellsfargo.payment.canonical.enums.PaymentDirection;
import com.wellsfargo.payment.canonical.enums.PaymentStatus;
import com.wellsfargo.payment.canonical.enums.RoutingNetwork;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Test runner to validate that InstdAmt is preserved and IntrBkSttlmAmt reflects net amount after fees.
 */
public class FeeAmountValidationTestRunner {
    
    private static final String WELLS_BIC = "WFBIUS6SXXX";
    
    public static void main(String[] args) {
        System.out.println("=".repeat(80));
        System.out.println("FEE AMOUNT VALIDATION TEST - InstdAmt vs IntrBkSttlmAmt");
        System.out.println("=".repeat(80));
        System.out.println();
        
        // Initialize services
        FeeLookupService feeLookupService = new FeeLookupService();
        feeLookupService.loadFeeSchedules();
        
        FeeCalculationService feeCalculationService = new FeeCalculationService();
        try {
            java.lang.reflect.Field field = FeeCalculationService.class.getDeclaredField("feeLookupService");
            field.setAccessible(true);
            field.set(feeCalculationService, feeLookupService);
        } catch (Exception e) {
            System.err.println("Error injecting feeLookupService: " + e.getMessage());
            return;
        }
        
        FeeApplicationService feeApplicationService = new FeeApplicationService();
        try {
            java.lang.reflect.Field field = FeeApplicationService.class.getDeclaredField("feeCalculationService");
            field.setAccessible(true);
            field.set(feeApplicationService, feeCalculationService);
        } catch (Exception e) {
            System.err.println("Error injecting feeCalculationService: " + e.getMessage());
            return;
        }
        
        // Test 1: SHA Charge Bearer (Fees Deducted)
        System.out.println("TEST 1: SHA Charge Bearer (Fees Deducted from Principal)");
        System.out.println("-".repeat(80));
        testShaChargeBearer(feeApplicationService);
        System.out.println();
        
        // Test 2: OUR Charge Bearer (Fees NOT Deducted)
        System.out.println("TEST 2: OUR Charge Bearer (Fees Billed Separately)");
        System.out.println("-".repeat(80));
        testOurChargeBearer(feeApplicationService);
        System.out.println();
        
        // Test 3: CRED Charge Bearer (Fees Deducted)
        System.out.println("TEST 3: CRED Charge Bearer (Fees Deducted from Principal)");
        System.out.println("-".repeat(80));
        testCredChargeBearer(feeApplicationService);
        System.out.println();
        
        System.out.println("=".repeat(80));
        System.out.println("ALL TESTS COMPLETED");
        System.out.println("=".repeat(80));
    }
    
    /**
     * Test SHA charge bearer: Fees should be deducted, InstdAmt preserved, IntrBkSttlmAmt reduced.
     */
    private static void testShaChargeBearer(FeeApplicationService service) {
        PaymentEvent originalEvent = createPaymentEvent("HDFCINBB", WELLS_BIC, PaymentDirection.INBOUND, "SHA", 
            new BigDecimal("10000.00"));
        
        // Apply fees
        PaymentEvent eventWithFees = service.applyFees(originalEvent);
        
        System.out.println("Payment: Inbound SWIFT from HDFC, Amount: $10,000.00");
        System.out.println("Charge Bearer: SHA (Shared)");
        System.out.println();
        System.out.println("Amount Fields:");
        System.out.println("  - InstdAmt (original): $" + eventWithFees.getAmount());
        System.out.println("  - IntrBkSttlmAmt (settlement): $" + 
            (eventWithFees.getSettlementAmount() != null ? eventWithFees.getSettlementAmount() : "NOT SET"));
        System.out.println();
        
        // Get fee info from enrichment
        if (eventWithFees.getEnrichmentContext() != null && 
            eventWithFees.getEnrichmentContext().getFeeCalculation() != null) {
            var feeInfo = eventWithFees.getEnrichmentContext().getFeeCalculation();
            System.out.println("Fee Information:");
            System.out.println("  - Fee Amount: $" + feeInfo.get("fee_amount"));
            System.out.println("  - Deduct From Principal: " + feeInfo.get("deduct_from_principal"));
            System.out.println("  - Fee Settlement: " + feeInfo.get("fee_settlement"));
            System.out.println();
        }
        
        // Validate
        boolean passed = true;
        BigDecimal originalAmount = new BigDecimal("10000.00");
        
        // InstdAmt should equal original amount (never changes)
        if (!eventWithFees.getAmount().equals(originalAmount)) {
            System.err.println("FAIL: InstdAmt should equal original amount ($10,000.00), got $" + eventWithFees.getAmount());
            passed = false;
        }
        
        // IntrBkSttlmAmt should be less than original (fees deducted)
        if (eventWithFees.getSettlementAmount() == null) {
            System.err.println("FAIL: IntrBkSttlmAmt is not set");
            passed = false;
        } else if (eventWithFees.getSettlementAmount().compareTo(originalAmount) >= 0) {
            System.err.println("FAIL: IntrBkSttlmAmt should be less than InstdAmt (fees deducted), got $" + 
                eventWithFees.getSettlementAmount());
            passed = false;
        }
        
        // Calculate expected settlement amount
        if (eventWithFees.getEnrichmentContext() != null && 
            eventWithFees.getEnrichmentContext().getFeeCalculation() != null) {
            var feeInfo = eventWithFees.getEnrichmentContext().getFeeCalculation();
            String feeAmountStr = (String) feeInfo.get("fee_amount");
            BigDecimal feeAmount = new BigDecimal(feeAmountStr);
            BigDecimal expectedSettlement = originalAmount.subtract(feeAmount);
            
            if (!eventWithFees.getSettlementAmount().equals(expectedSettlement)) {
                System.err.println("FAIL: IntrBkSttlmAmt should be $" + expectedSettlement + 
                    " (original - fee), got $" + eventWithFees.getSettlementAmount());
                passed = false;
            }
        }
        
        if (passed) {
            System.out.println("✓ TEST PASSED: InstdAmt preserved, IntrBkSttlmAmt correctly reduced by fees");
        } else {
            System.err.println("✗ TEST FAILED: Validation errors above");
        }
    }
    
    /**
     * Test OUR charge bearer: Fees should NOT be deducted, both amounts should be equal.
     */
    private static void testOurChargeBearer(FeeApplicationService service) {
        PaymentEvent originalEvent = createPaymentEvent("HDFCINBB", WELLS_BIC, PaymentDirection.INBOUND, "OUR", 
            new BigDecimal("10000.00"));
        
        // Apply fees
        PaymentEvent eventWithFees = service.applyFees(originalEvent);
        
        System.out.println("Payment: Inbound SWIFT from HDFC, Amount: $10,000.00");
        System.out.println("Charge Bearer: OUR (Sender pays)");
        System.out.println();
        System.out.println("Amount Fields:");
        System.out.println("  - InstdAmt (original): $" + eventWithFees.getAmount());
        System.out.println("  - IntrBkSttlmAmt (settlement): $" + 
            (eventWithFees.getSettlementAmount() != null ? eventWithFees.getSettlementAmount() : "NOT SET"));
        System.out.println();
        
        // Get fee info from enrichment
        if (eventWithFees.getEnrichmentContext() != null && 
            eventWithFees.getEnrichmentContext().getFeeCalculation() != null) {
            var feeInfo = eventWithFees.getEnrichmentContext().getFeeCalculation();
            System.out.println("Fee Information:");
            System.out.println("  - Fee Amount: $" + feeInfo.get("fee_amount"));
            System.out.println("  - Deduct From Principal: " + feeInfo.get("deduct_from_principal"));
            System.out.println("  - Fee Settlement: " + feeInfo.get("fee_settlement"));
            System.out.println();
        }
        
        // Validate
        boolean passed = true;
        BigDecimal originalAmount = new BigDecimal("10000.00");
        
        // InstdAmt should equal original amount
        if (!eventWithFees.getAmount().equals(originalAmount)) {
            System.err.println("FAIL: InstdAmt should equal original amount ($10,000.00), got $" + eventWithFees.getAmount());
            passed = false;
        }
        
        // IntrBkSttlmAmt should equal InstdAmt (fees NOT deducted)
        if (eventWithFees.getSettlementAmount() == null) {
            System.err.println("FAIL: IntrBkSttlmAmt is not set");
            passed = false;
        } else if (!eventWithFees.getSettlementAmount().equals(originalAmount)) {
            System.err.println("FAIL: IntrBkSttlmAmt should equal InstdAmt ($10,000.00) for OUR charge bearer, got $" + 
                eventWithFees.getSettlementAmount());
            passed = false;
        }
        
        if (passed) {
            System.out.println("✓ TEST PASSED: InstdAmt preserved, IntrBkSttlmAmt equals InstdAmt (fees billed separately)");
        } else {
            System.err.println("✗ TEST FAILED: Validation errors above");
        }
    }
    
    /**
     * Test CRED charge bearer: Fees should be deducted, InstdAmt preserved, IntrBkSttlmAmt reduced.
     */
    private static void testCredChargeBearer(FeeApplicationService service) {
        PaymentEvent originalEvent = createPaymentEvent("HDFCINBB", WELLS_BIC, PaymentDirection.INBOUND, "CRED", 
            new BigDecimal("10000.00"));
        
        // Apply fees
        PaymentEvent eventWithFees = service.applyFees(originalEvent);
        
        System.out.println("Payment: Inbound SWIFT from HDFC, Amount: $10,000.00");
        System.out.println("Charge Bearer: CRED (Receiver pays)");
        System.out.println();
        System.out.println("Amount Fields:");
        System.out.println("  - InstdAmt (original): $" + eventWithFees.getAmount());
        System.out.println("  - IntrBkSttlmAmt (settlement): $" + 
            (eventWithFees.getSettlementAmount() != null ? eventWithFees.getSettlementAmount() : "NOT SET"));
        System.out.println();
        
        // Get fee info from enrichment
        if (eventWithFees.getEnrichmentContext() != null && 
            eventWithFees.getEnrichmentContext().getFeeCalculation() != null) {
            var feeInfo = eventWithFees.getEnrichmentContext().getFeeCalculation();
            System.out.println("Fee Information:");
            System.out.println("  - Fee Amount: $" + feeInfo.get("fee_amount"));
            System.out.println("  - Deduct From Principal: " + feeInfo.get("deduct_from_principal"));
            System.out.println("  - Fee Settlement: " + feeInfo.get("fee_settlement"));
            System.out.println();
        }
        
        // Validate
        boolean passed = true;
        BigDecimal originalAmount = new BigDecimal("10000.00");
        
        // InstdAmt should equal original amount
        if (!eventWithFees.getAmount().equals(originalAmount)) {
            System.err.println("FAIL: InstdAmt should equal original amount ($10,000.00), got $" + eventWithFees.getAmount());
            passed = false;
        }
        
        // IntrBkSttlmAmt should be less than original (fees deducted)
        if (eventWithFees.getSettlementAmount() == null) {
            System.err.println("FAIL: IntrBkSttlmAmt is not set");
            passed = false;
        } else if (eventWithFees.getSettlementAmount().compareTo(originalAmount) >= 0) {
            System.err.println("FAIL: IntrBkSttlmAmt should be less than InstdAmt (fees deducted), got $" + 
                eventWithFees.getSettlementAmount());
            passed = false;
        }
        
        if (passed) {
            System.out.println("✓ TEST PASSED: InstdAmt preserved, IntrBkSttlmAmt correctly reduced by fees");
        } else {
            System.err.println("✗ TEST FAILED: Validation errors above");
        }
    }
    
    /**
     * Create PaymentEvent for testing.
     */
    private static PaymentEvent createPaymentEvent(String debtorBic, String creditorBic, 
                                                   PaymentDirection direction, String chargeBearer,
                                                   BigDecimal amount) {
        Agent debtorAgent = Agent.builder()
            .idScheme("BIC")
            .idValue(debtorBic)
            .country("US")
            .build();
        
        Agent creditorAgent = Agent.builder()
            .idScheme("BIC")
            .idValue(creditorBic)
            .country("US")
            .build();
        
        // Create routing context with SWIFT network
        RoutingContext routingContext = RoutingContext.builder()
            .selectedNetwork(RoutingNetwork.SWIFT)
            .build();
        
        return PaymentEvent.builder()
            .msgId("TEST-MSG-" + Instant.now().toEpochMilli())
            .endToEndId("TEST-E2E-" + Instant.now().toEpochMilli())
            .transactionId("TEST-TXN-" + Instant.now().toEpochMilli())
            .amount(amount)
            .currency("USD")
            .direction(direction)
            .sourceMessageType(MessageSource.ISO20022_PACS008)
            .debtorAgent(debtorAgent)
            .creditorAgent(creditorAgent)
            .debtor(Party.builder().name("Debtor Customer").country("US").build())
            .creditor(Party.builder().name("Creditor Customer").country("US").build())
            .status(PaymentStatus.RECEIVED)
            .chargeBearer(chargeBearer)
            .routingContext(routingContext)
            .valueDate(Instant.now().toString())
            .settlementDate(Instant.now().toString())
            .createdTimestamp(Instant.now().toString())
            .lastUpdatedTimestamp(Instant.now().toString())
            .build();
    }
}

