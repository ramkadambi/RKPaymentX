package com.wellsfargo.payment.satellites.paymentposting;

import com.wellsfargo.payment.canonical.*;
import com.wellsfargo.payment.canonical.enums.RoutingNetwork;
import com.wellsfargo.payment.satellites.balancecheck.SettlementAccountLookupService;

import java.math.BigDecimal;

/**
 * Standalone Reconciliation Test Runner.
 * 
 * Tests payment posting reconciliation without requiring JUnit or Spring Boot.
 * Run this class directly to execute all reconciliation tests.
 */
public class ReconciliationTestRunner {
    
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println(" Payment Reconciliation Test");
        System.out.println("========================================");
        System.out.println();
        
        // Initialize services
        ReconciliationValidator validator = new ReconciliationValidator();
        validator.loadLedger();
        
        SettlementAccountLookupService lookupService = new SettlementAccountLookupService();
        // Initialize the lookup service (loads accounts from JSON)
        try {
            java.lang.reflect.Method initMethod = SettlementAccountLookupService.class.getDeclaredMethod("init");
            initMethod.setAccessible(true);
            initMethod.invoke(lookupService);
        } catch (Exception e) {
            System.err.println("Warning: Could not initialize SettlementAccountLookupService: " + e.getMessage());
        }
        PaymentPostingServiceWrapper postingService = new PaymentPostingServiceWrapper(lookupService);
        
        // Run all tests
        int passed = 0;
        int failed = 0;
        
        try {
            if (testSwiftInboundToWellsCustomer(validator, postingService)) passed++; else failed++;
            if (testSwiftInboundToExternalBank(validator, postingService)) passed++; else failed++;
            if (testFedInboundToWellsCustomer(validator, postingService)) passed++; else failed++;
            if (testChipsInboundToWellsCustomer(validator, postingService)) passed++; else failed++;
            if (testWellsOutboundFed(validator, postingService)) passed++; else failed++;
            if (testWellsOutboundChips(validator, postingService)) passed++; else failed++;
            if (testWellsOutboundSwift(validator, postingService)) passed++; else failed++;
        } catch (Exception e) {
            System.err.println("Error running tests: " + e.getMessage());
            e.printStackTrace();
            failed++;
        }
        
        // Summary
        System.out.println();
        System.out.println("========================================");
        System.out.println(" Test Summary");
        System.out.println("========================================");
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);
        System.out.println("Total:  " + (passed + failed));
        System.out.println();
        
        if (failed == 0) {
            System.out.println("✓ All reconciliation tests passed!");
            System.exit(0);
        } else {
            System.out.println("✗ Some tests failed!");
            System.exit(1);
        }
    }
    
    private static boolean testSwiftInboundToWellsCustomer(ReconciliationValidator validator, PaymentPostingServiceWrapper postingService) {
        System.out.println("\n=== Test 1: SWIFT Inbound to Wells Customer ===");
        
        PaymentEvent payment = createPaymentEvent(
            "E2E-TEST-001",
            "SBININBB", "IN",
            "WFBIUS6S", "US",
            new BigDecimal("5000.00"),
            "USD",
            RoutingNetwork.SWIFT
        );
        
        PaymentPostingService.SettlementAccountInfo settlementInfo = postingService.determineSettlementAccountsPublic(payment);
        printSettlementInfo(settlementInfo, payment.getAmount());
        
        ReconciliationValidator.ReconciliationResult result = validator.validateReconciliation(
            payment,
            settlementInfo.getDebitAccount(),
            settlementInfo.getDebitAccountType(),
            settlementInfo.getCreditAccount(),
            settlementInfo.getCreditAccountType(),
            payment.getAmount()
        );
        
        printReconciliationResult(result);
        
        boolean valid = result.isValid() && 
                       "VOSTRO".equals(settlementInfo.getDebitAccountType()) &&
                       "CUSTOMER".equals(settlementInfo.getCreditAccountType());
        
        System.out.println(valid ? "✓ PASSED" : "✗ FAILED");
        return valid;
    }
    
    private static boolean testSwiftInboundToExternalBank(ReconciliationValidator validator, PaymentPostingServiceWrapper postingService) {
        System.out.println("\n=== Test 2: SWIFT Inbound to External Bank (SWIFT) ===");
        
        PaymentEvent payment = createPaymentEvent(
            "E2E-TEST-002",
            "SBININBB", "IN",
            "DEUTDEFF", "DE",
            new BigDecimal("10000.00"),
            "USD",
            RoutingNetwork.SWIFT
        );
        
        PaymentPostingService.SettlementAccountInfo settlementInfo = postingService.determineSettlementAccountsPublic(payment);
        printSettlementInfo(settlementInfo, payment.getAmount());
        
        ReconciliationValidator.ReconciliationResult result = validator.validateReconciliation(
            payment,
            settlementInfo.getDebitAccount(),
            settlementInfo.getDebitAccountType(),
            settlementInfo.getCreditAccount(),
            settlementInfo.getCreditAccountType(),
            payment.getAmount()
        );
        
        printReconciliationResult(result);
        
        boolean valid = result.isValid() && 
                       "VOSTRO".equals(settlementInfo.getDebitAccountType()) &&
                       "SWIFT_NOSTRO".equals(settlementInfo.getCreditAccountType());
        
        System.out.println(valid ? "✓ PASSED" : "✗ FAILED");
        return valid;
    }
    
    private static boolean testFedInboundToWellsCustomer(ReconciliationValidator validator, PaymentPostingServiceWrapper postingService) {
        System.out.println("\n=== Test 3: FED Inbound to Wells Customer ===");
        System.out.println("(Note: FED/CHIPS inbound payments are assumed to be for Wells customers only)");
        
        PaymentEvent payment = createPaymentEvent(
            "E2E-TEST-003",
            "HSBCGB2L", "GB",  // Debtor: HSBC (foreign bank)
            "WFBIUS6S", "US",  // Creditor: Wells Fargo (Wells customer)
            new BigDecimal("15000.00"),
            "USD",
            RoutingNetwork.FED
        );
        
        PaymentPostingService.SettlementAccountInfo settlementInfo = postingService.determineSettlementAccountsPublic(payment);
        printSettlementInfo(settlementInfo, payment.getAmount());
        
        ReconciliationValidator.ReconciliationResult result = validator.validateReconciliation(
            payment,
            settlementInfo.getDebitAccount(),
            settlementInfo.getDebitAccountType(),
            settlementInfo.getCreditAccount(),
            settlementInfo.getCreditAccountType(),
            payment.getAmount()
        );
        
        printReconciliationResult(result);
        
        boolean valid = result.isValid() && 
                       "VOSTRO".equals(settlementInfo.getDebitAccountType()) &&
                       "CUSTOMER".equals(settlementInfo.getCreditAccountType());
        
        System.out.println(valid ? "✓ PASSED" : "✗ FAILED");
        return valid;
    }
    
    private static boolean testChipsInboundToWellsCustomer(ReconciliationValidator validator, PaymentPostingServiceWrapper postingService) {
        System.out.println("\n=== Test 4: CHIPS Inbound to Wells Customer ===");
        System.out.println("(Note: FED/CHIPS inbound payments are assumed to be for Wells customers only)");
        
        PaymentEvent payment = createPaymentEvent(
            "E2E-TEST-004",
            "DEUTDEFF", "DE",  // Debtor: Deutsche Bank (foreign bank)
            "WFBIUS6S", "US",  // Creditor: Wells Fargo (Wells customer)
            new BigDecimal("20000.00"),
            "USD",
            RoutingNetwork.CHIPS
        );
        
        PaymentPostingService.SettlementAccountInfo settlementInfo = postingService.determineSettlementAccountsPublic(payment);
        printSettlementInfo(settlementInfo, payment.getAmount());
        
        ReconciliationValidator.ReconciliationResult result = validator.validateReconciliation(
            payment,
            settlementInfo.getDebitAccount(),
            settlementInfo.getDebitAccountType(),
            settlementInfo.getCreditAccount(),
            settlementInfo.getCreditAccountType(),
            payment.getAmount()
        );
        
        printReconciliationResult(result);
        
        boolean valid = result.isValid() && 
                       "VOSTRO".equals(settlementInfo.getDebitAccountType()) &&
                       "CUSTOMER".equals(settlementInfo.getCreditAccountType());
        
        System.out.println(valid ? "✓ PASSED" : "✗ FAILED");
        return valid;
    }
    
    private static boolean testWellsOutboundFed(ReconciliationValidator validator, PaymentPostingServiceWrapper postingService) {
        System.out.println("\n=== Test 5: Wells-Initiated Outbound via FED ===");
        
        PaymentEvent payment = createPaymentEvent(
            "E2E-TEST-005",
            "WFBIUS6S", "US",
            "CHASUS33", "US",
            new BigDecimal("25000.00"),
            "USD",
            RoutingNetwork.FED
        );
        
        PaymentPostingService.SettlementAccountInfo settlementInfo = postingService.determineSettlementAccountsPublic(payment);
        printSettlementInfo(settlementInfo, payment.getAmount());
        
        ReconciliationValidator.ReconciliationResult result = validator.validateReconciliation(
            payment,
            settlementInfo.getDebitAccount(),
            settlementInfo.getDebitAccountType(),
            settlementInfo.getCreditAccount(),
            settlementInfo.getCreditAccountType(),
            payment.getAmount()
        );
        
        printReconciliationResult(result);
        
        boolean valid = result.isValid() && 
                       "CUSTOMER".equals(settlementInfo.getDebitAccountType()) &&
                       "FED".equals(settlementInfo.getCreditAccountType());
        
        System.out.println(valid ? "✓ PASSED" : "✗ FAILED");
        return valid;
    }
    
    private static boolean testWellsOutboundChips(ReconciliationValidator validator, PaymentPostingServiceWrapper postingService) {
        System.out.println("\n=== Test 6: Wells-Initiated Outbound via CHIPS ===");
        
        PaymentEvent payment = createPaymentEvent(
            "E2E-TEST-006",
            "WFBIUS6S", "US",
            "BOFAUS3N", "US",
            new BigDecimal("30000.00"),
            "USD",
            RoutingNetwork.CHIPS
        );
        
        PaymentPostingService.SettlementAccountInfo settlementInfo = postingService.determineSettlementAccountsPublic(payment);
        printSettlementInfo(settlementInfo, payment.getAmount());
        
        ReconciliationValidator.ReconciliationResult result = validator.validateReconciliation(
            payment,
            settlementInfo.getDebitAccount(),
            settlementInfo.getDebitAccountType(),
            settlementInfo.getCreditAccount(),
            settlementInfo.getCreditAccountType(),
            payment.getAmount()
        );
        
        printReconciliationResult(result);
        
        boolean valid = result.isValid() && 
                       "CUSTOMER".equals(settlementInfo.getDebitAccountType()) &&
                       "CHIPS_NOSTRO".equals(settlementInfo.getCreditAccountType());
        
        System.out.println(valid ? "✓ PASSED" : "✗ FAILED");
        return valid;
    }
    
    private static boolean testWellsOutboundSwift(ReconciliationValidator validator, PaymentPostingServiceWrapper postingService) {
        System.out.println("\n=== Test 7: Wells-Initiated Outbound via SWIFT ===");
        
        // Use a different Wells customer account to avoid insufficient balance
        // Note: Using WFBIUS6S but with sufficient balance restored from previous tests
        PaymentEvent payment = createPaymentEvent(
            "E2E-TEST-007",
            "WFBIUS6S", "US",  // Debtor: Wells customer
            "HDFCINBB", "IN",
            new BigDecimal("30000.00"),  // Reduced amount to fit available balance
            "USD",
            RoutingNetwork.SWIFT
        );
        
        PaymentPostingService.SettlementAccountInfo settlementInfo = postingService.determineSettlementAccountsPublic(payment);
        printSettlementInfo(settlementInfo, payment.getAmount());
        
        ReconciliationValidator.ReconciliationResult result = validator.validateReconciliation(
            payment,
            settlementInfo.getDebitAccount(),
            settlementInfo.getDebitAccountType(),
            settlementInfo.getCreditAccount(),
            settlementInfo.getCreditAccountType(),
            payment.getAmount()
        );
        
        printReconciliationResult(result);
        
        boolean valid = result.isValid() && 
                       "CUSTOMER".equals(settlementInfo.getDebitAccountType()) &&
                       "SWIFT_NOSTRO".equals(settlementInfo.getCreditAccountType());
        
        System.out.println(valid ? "✓ PASSED" : "✗ FAILED");
        return valid;
    }
    
    private static PaymentEvent createPaymentEvent(
            String endToEndId,
            String debtorBic, String debtorCountry,
            String creditorBic, String creditorCountry,
            BigDecimal amount,
            String currency,
            RoutingNetwork selectedNetwork) {
        
        Agent debtorAgent = Agent.builder()
            .idScheme("BIC")
            .idValue(debtorBic)
            .country(debtorCountry)
            .build();
        
        Agent creditorAgent = Agent.builder()
            .idScheme("BIC")
            .idValue(creditorBic)
            .country(creditorCountry)
            .build();
        
        RoutingContext routingContext = RoutingContext.builder()
            .selectedNetwork(selectedNetwork)
            .build();
        
        return PaymentEvent.builder()
            .msgId("MSG-" + endToEndId)
            .endToEndId(endToEndId)
            .transactionId("TXN-" + endToEndId)
            .debtorAgent(debtorAgent)
            .creditorAgent(creditorAgent)
            .amount(amount)
            .currency(currency)
            .routingContext(routingContext)
            .build();
    }
    
    private static void printSettlementInfo(PaymentPostingService.SettlementAccountInfo info, BigDecimal amount) {
        System.out.println("Debit Account:  " + info.getDebitAccount() + " (" + info.getDebitAccountType() + ")");
        System.out.println("Credit Account: " + info.getCreditAccount() + " (" + info.getCreditAccountType() + ")");
        System.out.println("Amount:         " + amount + " USD");
    }
    
    private static void printReconciliationResult(ReconciliationValidator.ReconciliationResult result) {
        System.out.println("\n--- Reconciliation Result ---");
        System.out.println("Valid: " + result.isValid());
        System.out.println("Debit Account: " + result.getDebitAccount() + " (" + result.getDebitAccountType() + ")");
        System.out.println("  Balance Before: " + result.getDebitBalanceBefore());
        System.out.println("  Balance After:  " + result.getDebitBalanceAfter());
        System.out.println("Credit Account: " + result.getCreditAccount() + " (" + result.getCreditAccountType() + ")");
        System.out.println("  Balance Before: " + result.getCreditBalanceBefore());
        System.out.println("  Balance After:  " + result.getCreditBalanceAfter());
        
        if (!result.getErrors().isEmpty()) {
            System.out.println("\nErrors:");
            result.getErrors().forEach(error -> System.out.println("  - " + error));
        }
        
        if (!result.getWarnings().isEmpty()) {
            System.out.println("\nWarnings:");
            result.getWarnings().forEach(warning -> System.out.println("  - " + warning));
        }
    }
    
    /**
     * Wrapper for PaymentPostingService to enable testing without Spring.
     */
    private static class PaymentPostingServiceWrapper {
        private final SettlementAccountLookupService lookupService;
        
        public PaymentPostingServiceWrapper(SettlementAccountLookupService lookupService) {
            this.lookupService = lookupService;
        }
        
        public PaymentPostingService.SettlementAccountInfo determineSettlementAccountsPublic(PaymentEvent event) {
            // Create a minimal PaymentPostingService instance for testing
            // We'll use reflection to access the private method
            try {
                PaymentPostingService service = new PaymentPostingService();
                java.lang.reflect.Field field = PaymentPostingService.class.getDeclaredField("settlementAccountLookupService");
                field.setAccessible(true);
                field.set(service, lookupService);
                
                java.lang.reflect.Method method = PaymentPostingService.class.getDeclaredMethod("determineSettlementAccounts", PaymentEvent.class);
                method.setAccessible(true);
                return (PaymentPostingService.SettlementAccountInfo) method.invoke(service, event);
            } catch (Exception e) {
                throw new RuntimeException("Failed to determine settlement accounts", e);
            }
        }
    }
}

