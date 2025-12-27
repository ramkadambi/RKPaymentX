package com.wellsfargo.payment.satellites.fees;

import com.wellsfargo.payment.canonical.*;
import com.wellsfargo.payment.canonical.enums.MessageSource;
import com.wellsfargo.payment.canonical.enums.PaymentDirection;
import com.wellsfargo.payment.canonical.enums.PaymentStatus;
import com.wellsfargo.payment.canonical.enums.RoutingNetwork;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Test runner to validate fee calculation logic (default vs negotiated fees).
 */
public class FeeCalculationTestRunner {
    
    private static final String WELLS_BIC = "WFBIUS6SXXX";
    
    public static void main(String[] args) {
        System.out.println("=".repeat(80));
        System.out.println("FEE CALCULATION VALIDATION TEST");
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
        
        // Test 1: Inbound SWIFT from HDFC (negotiated fee)
        System.out.println("TEST 1: Inbound SWIFT from HDFC Bank (Negotiated Fee Expected)");
        System.out.println("-".repeat(80));
        testInboundSwiftFromHDFC(feeCalculationService);
        System.out.println();
        
        // Test 2: Inbound SWIFT from unknown bank (default fee)
        System.out.println("TEST 2: Inbound SWIFT from Unknown Bank (Default Fee Expected)");
        System.out.println("-".repeat(80));
        testInboundSwiftFromUnknownBank(feeCalculationService);
        System.out.println();
        
        // Test 3: Outbound FED to JPMC (negotiated fee)
        System.out.println("TEST 3: Outbound FED to JPMC (Negotiated Fee Expected)");
        System.out.println("-".repeat(80));
        testOutboundFedToJPMC(feeCalculationService);
        System.out.println();
        
        // Test 4: Outbound CHIPS to JPMC (negotiated fee)
        System.out.println("TEST 4: Outbound CHIPS to JPMC (Negotiated Fee Expected)");
        System.out.println("-".repeat(80));
        testOutboundChipsToJPMC(feeCalculationService);
        System.out.println();
        
        // Test 5: Fee with CRED charge bearer (deduct from principal)
        System.out.println("TEST 5: Fee Calculation with CRED Charge Bearer (Deduct from Principal)");
        System.out.println("-".repeat(80));
        testFeeWithCredChargeBearer(feeCalculationService);
        System.out.println();
        
        // Test 6: Fee with OUR charge bearer (billing, don't deduct)
        System.out.println("TEST 6: Fee Calculation with OUR Charge Bearer (Billing, Don't Deduct)");
        System.out.println("-".repeat(80));
        testFeeWithOurChargeBearer(feeCalculationService);
        System.out.println();
        
        System.out.println("=".repeat(80));
        System.out.println("ALL TESTS COMPLETED");
        System.out.println("=".repeat(80));
    }
    
    /**
     * Test inbound SWIFT from HDFC (should use negotiated fee: $20.00 instead of default $25.00).
     */
    private static void testInboundSwiftFromHDFC(FeeCalculationService service) {
        PaymentEvent event = createPaymentEvent("HDFCINBB", WELLS_BIC, PaymentDirection.INBOUND, "SHA");
        FeeCalculationService.FeeCalculationResult result = service.calculateFee(event, RoutingNetwork.SWIFT, false);
        
        System.out.println("Payment: Inbound SWIFT from HDFC Bank (HDFCINBB)");
        System.out.println("Charge Bearer: SHA (Shared)");
        System.out.println();
        System.out.println("Fee Calculation Result:");
        System.out.println("  - Fee Amount: $" + result.getFeeAmount());
        System.out.println("  - Fee Type: " + result.getFeeType());
        System.out.println("  - Is Negotiated Fee: " + result.isNegotiatedFee());
        System.out.println("  - Deduct From Principal: " + result.isDeductFromPrincipal());
        System.out.println("  - Fee Settlement: " + result.getFeeSettlement());
        System.out.println();
        
        boolean passed = true;
        BigDecimal expectedFee = new BigDecimal("20.00");
        if (!result.getFeeAmount().equals(expectedFee)) {
            System.err.println("FAIL: Expected fee amount $20.00 (negotiated), got $" + result.getFeeAmount());
            passed = false;
        }
        if (!result.isNegotiatedFee()) {
            System.err.println("FAIL: Expected negotiated fee, but default fee was used");
            passed = false;
        }
        if (!"inbound_swift".equals(result.getFeeType())) {
            System.err.println("FAIL: Expected fee type 'inbound_swift'");
            passed = false;
        }
        
        if (passed) {
            System.out.println("✓ TEST PASSED: Negotiated fee correctly applied for HDFC");
        } else {
            System.err.println("✗ TEST FAILED: Validation errors above");
        }
    }
    
    /**
     * Test inbound SWIFT from unknown bank (should use default fee: $25.00).
     */
    private static void testInboundSwiftFromUnknownBank(FeeCalculationService service) {
        PaymentEvent event = createPaymentEvent("UNKNOWNXX", WELLS_BIC, PaymentDirection.INBOUND, "SHA");
        FeeCalculationService.FeeCalculationResult result = service.calculateFee(event, RoutingNetwork.SWIFT, false);
        
        System.out.println("Payment: Inbound SWIFT from Unknown Bank (UNKNOWNXX)");
        System.out.println("Charge Bearer: SHA (Shared)");
        System.out.println();
        System.out.println("Fee Calculation Result:");
        System.out.println("  - Fee Amount: $" + result.getFeeAmount());
        System.out.println("  - Fee Type: " + result.getFeeType());
        System.out.println("  - Is Negotiated Fee: " + result.isNegotiatedFee());
        System.out.println("  - Deduct From Principal: " + result.isDeductFromPrincipal());
        System.out.println();
        
        boolean passed = true;
        BigDecimal expectedFee = new BigDecimal("25.00");
        if (!result.getFeeAmount().equals(expectedFee)) {
            System.err.println("FAIL: Expected fee amount $25.00 (default), got $" + result.getFeeAmount());
            passed = false;
        }
        if (result.isNegotiatedFee()) {
            System.err.println("FAIL: Expected default fee, but negotiated fee was used");
            passed = false;
        }
        
        if (passed) {
            System.out.println("✓ TEST PASSED: Default fee correctly applied for unknown bank");
        } else {
            System.err.println("✗ TEST FAILED: Validation errors above");
        }
    }
    
    /**
     * Test outbound FED to JPMC (should use negotiated fee if available, otherwise default).
     */
    private static void testOutboundFedToJPMC(FeeCalculationService service) {
        PaymentEvent event = createPaymentEvent(WELLS_BIC, "CHASUS33", PaymentDirection.OUTBOUND, "SHA");
        FeeCalculationService.FeeCalculationResult result = service.calculateFee(event, RoutingNetwork.FED, false);
        
        System.out.println("Payment: Outbound FED to JPMC (CHASUS33)");
        System.out.println("Charge Bearer: SHA (Shared)");
        System.out.println();
        System.out.println("Fee Calculation Result:");
        System.out.println("  - Fee Amount: $" + result.getFeeAmount());
        System.out.println("  - Fee Type: " + result.getFeeType());
        System.out.println("  - Is Negotiated Fee: " + result.isNegotiatedFee());
        System.out.println();
        
        boolean passed = true;
        BigDecimal expectedFee = new BigDecimal("20.00"); // Default outbound_fed fee (JPMC doesn't have negotiated FED fee)
        if (!result.getFeeAmount().equals(expectedFee)) {
            System.err.println("FAIL: Expected fee amount $20.00 (default outbound_fed), got $" + result.getFeeAmount());
            passed = false;
        }
        if (!"outbound_fed".equals(result.getFeeType())) {
            System.err.println("FAIL: Expected fee type 'outbound_fed'");
            passed = false;
        }
        
        if (passed) {
            System.out.println("✓ TEST PASSED: Default fee correctly applied (JPMC has negotiated CHIPS fee, not FED)");
        } else {
            System.err.println("✗ TEST FAILED: Validation errors above");
        }
    }
    
    /**
     * Test outbound CHIPS to JPMC (should use negotiated fee: $12.00 instead of default $15.00).
     */
    private static void testOutboundChipsToJPMC(FeeCalculationService service) {
        PaymentEvent event = createPaymentEvent(WELLS_BIC, "CHASUS33", PaymentDirection.OUTBOUND, "SHA");
        FeeCalculationService.FeeCalculationResult result = service.calculateFee(event, RoutingNetwork.CHIPS, false);
        
        System.out.println("Payment: Outbound CHIPS to JPMC (CHASUS33)");
        System.out.println("Charge Bearer: SHA (Shared)");
        System.out.println();
        System.out.println("Fee Calculation Result:");
        System.out.println("  - Fee Amount: $" + result.getFeeAmount());
        System.out.println("  - Fee Type: " + result.getFeeType());
        System.out.println("  - Is Negotiated Fee: " + result.isNegotiatedFee());
        System.out.println();
        
        boolean passed = true;
        BigDecimal expectedFee = new BigDecimal("12.00");
        if (!result.getFeeAmount().equals(expectedFee)) {
            System.err.println("FAIL: Expected fee amount $12.00 (negotiated), got $" + result.getFeeAmount());
            passed = false;
        }
        if (!result.isNegotiatedFee()) {
            System.err.println("FAIL: Expected negotiated fee, but default fee was used");
            passed = false;
        }
        if (!"outbound_chips".equals(result.getFeeType())) {
            System.err.println("FAIL: Expected fee type 'outbound_chips'");
            passed = false;
        }
        
        if (passed) {
            System.out.println("✓ TEST PASSED: Negotiated fee correctly applied for JPMC CHIPS");
        } else {
            System.err.println("✗ TEST FAILED: Validation errors above");
        }
    }
    
    /**
     * Test fee with CRED charge bearer (should deduct from principal).
     */
    private static void testFeeWithCredChargeBearer(FeeCalculationService service) {
        PaymentEvent event = createPaymentEvent("HDFCINBB", WELLS_BIC, PaymentDirection.INBOUND, "CRED");
        FeeCalculationService.FeeCalculationResult result = service.calculateFee(event, RoutingNetwork.SWIFT, false);
        
        BigDecimal originalAmount = new BigDecimal("10000.00");
        BigDecimal netAmount = service.calculateNetAmount(originalAmount, result.getFeeAmount(), result.isDeductFromPrincipal());
        
        System.out.println("Payment: Inbound SWIFT from HDFC, Amount: $10,000.00");
        System.out.println("Charge Bearer: CRED (Receiver pays)");
        System.out.println();
        System.out.println("Fee Calculation Result:");
        System.out.println("  - Fee Amount: $" + result.getFeeAmount());
        System.out.println("  - Deduct From Principal: " + result.isDeductFromPrincipal());
        System.out.println("  - Fee Settlement: " + result.getFeeSettlement());
        System.out.println("  - Original Amount: $" + originalAmount);
        System.out.println("  - Net Amount (after fee): $" + netAmount);
        System.out.println();
        
        boolean passed = true;
        if (!result.isDeductFromPrincipal()) {
            System.err.println("FAIL: Expected deductFromPrincipal=true for CRED charge bearer");
            passed = false;
        }
        if (!"DEDUCTED".equals(result.getFeeSettlement())) {
            System.err.println("FAIL: Expected feeSettlement=DEDUCTED for CRED charge bearer");
            passed = false;
        }
        BigDecimal expectedNet = originalAmount.subtract(result.getFeeAmount());
        if (!netAmount.equals(expectedNet)) {
            System.err.println("FAIL: Expected net amount $" + expectedNet + ", got $" + netAmount);
            passed = false;
        }
        
        if (passed) {
            System.out.println("✓ TEST PASSED: Fee correctly deducted from principal for CRED");
        } else {
            System.err.println("✗ TEST FAILED: Validation errors above");
        }
    }
    
    /**
     * Test fee with OUR charge bearer (should NOT deduct from principal, bill separately).
     */
    private static void testFeeWithOurChargeBearer(FeeCalculationService service) {
        PaymentEvent event = createPaymentEvent("HDFCINBB", WELLS_BIC, PaymentDirection.INBOUND, "OUR");
        FeeCalculationService.FeeCalculationResult result = service.calculateFee(event, RoutingNetwork.SWIFT, false);
        
        BigDecimal originalAmount = new BigDecimal("10000.00");
        BigDecimal netAmount = service.calculateNetAmount(originalAmount, result.getFeeAmount(), result.isDeductFromPrincipal());
        
        System.out.println("Payment: Inbound SWIFT from HDFC, Amount: $10,000.00");
        System.out.println("Charge Bearer: OUR (Sender pays)");
        System.out.println();
        System.out.println("Fee Calculation Result:");
        System.out.println("  - Fee Amount: $" + result.getFeeAmount());
        System.out.println("  - Deduct From Principal: " + result.isDeductFromPrincipal());
        System.out.println("  - Fee Settlement: " + result.getFeeSettlement());
        System.out.println("  - Original Amount: $" + originalAmount);
        System.out.println("  - Net Amount (after fee): $" + netAmount);
        System.out.println();
        
        boolean passed = true;
        if (result.isDeductFromPrincipal()) {
            System.err.println("FAIL: Expected deductFromPrincipal=false for OUR charge bearer");
            passed = false;
        }
        if (!"BILLING".equals(result.getFeeSettlement())) {
            System.err.println("FAIL: Expected feeSettlement=BILLING for OUR charge bearer");
            passed = false;
        }
        if (!netAmount.equals(originalAmount)) {
            System.err.println("FAIL: Expected net amount to equal original amount ($" + originalAmount + 
                ") for OUR charge bearer, got $" + netAmount);
            passed = false;
        }
        
        if (passed) {
            System.out.println("✓ TEST PASSED: Fee correctly NOT deducted from principal for OUR (billed separately)");
        } else {
            System.err.println("✗ TEST FAILED: Validation errors above");
        }
    }
    
    /**
     * Create PaymentEvent for testing.
     */
    private static PaymentEvent createPaymentEvent(String debtorBic, String creditorBic, 
                                                   PaymentDirection direction, String chargeBearer) {
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
        
        return PaymentEvent.builder()
            .msgId("TEST-MSG-" + Instant.now().toEpochMilli())
            .endToEndId("TEST-E2E-" + Instant.now().toEpochMilli())
            .transactionId("TEST-TXN-" + Instant.now().toEpochMilli())
            .amount(new BigDecimal("10000.00"))
            .currency("USD")
            .direction(direction)
            .sourceMessageType(MessageSource.ISO20022_PACS008)
            .debtorAgent(debtorAgent)
            .creditorAgent(creditorAgent)
            .debtor(Party.builder().name("Debtor Customer").country("US").build())
            .creditor(Party.builder().name("Creditor Customer").country("US").build())
            .status(PaymentStatus.RECEIVED)
            .chargeBearer(chargeBearer)
            .valueDate(Instant.now().toString())
            .settlementDate(Instant.now().toString())
            .createdTimestamp(Instant.now().toString())
            .lastUpdatedTimestamp(Instant.now().toString())
            .build();
    }
}

