package com.wellsfargo.payment.satellites.paymentposting;

import com.wellsfargo.payment.canonical.*;
import com.wellsfargo.payment.canonical.enums.RoutingNetwork;
import com.wellsfargo.payment.satellites.balancecheck.SettlementAccountLookupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reconciliation Test for Payment Posting.
 * 
 * Tests that debits and credits are correctly applied across:
 * - Vostro accounts (inbound payments)
 * - Nostro accounts (CHIPS/SWIFT)
 * - FED settlement account
 * - Customer accounts
 */
@SpringBootTest
public class ReconciliationTest {
    
    @Autowired
    private ReconciliationValidator reconciliationValidator;
    
    @Autowired
    private SettlementAccountLookupService settlementAccountLookupService;
    
    @Autowired
    private PaymentPostingService paymentPostingService;
    
    @BeforeEach
    public void setUp() {
        reconciliationValidator.loadLedger();
    }
    
    /**
     * Test 1: SWIFT Inbound - Beneficiary is Wells Customer
     * Expected: Debit VOSTRO, Credit CUSTOMER
     */
    @Test
    public void testSwiftInboundToWellsCustomer() {
        System.out.println("\n=== Test 1: SWIFT Inbound to Wells Customer ===");
        
        // Create payment: SBI (India) -> Wells Fargo Customer
        PaymentEvent payment = createPaymentEvent(
            "E2E-TEST-001",
            "SBININBB", "IN",  // Debtor: SBI
            "WFBIUS6S", "US",  // Creditor: Wells Fargo
            new BigDecimal("5000.00"),
            "USD",
            RoutingNetwork.SWIFT
        );
        
        // Determine settlement accounts
        PaymentPostingService.SettlementAccountInfo settlementInfo = paymentPostingService.determineSettlementAccountsPublic(payment);
        
        System.out.println("Debit Account: " + settlementInfo.getDebitAccount() + " (" + settlementInfo.getDebitAccountType() + ")");
        System.out.println("Credit Account: " + settlementInfo.getCreditAccount() + " (" + settlementInfo.getCreditAccountType() + ")");
        System.out.println("Amount: " + payment.getAmount() + " " + payment.getCurrency());
        
        // Validate reconciliation
        ReconciliationValidator.ReconciliationResult result = reconciliationValidator.validateReconciliation(
            payment,
            settlementInfo.getDebitAccount(),
            settlementInfo.getDebitAccountType(),
            settlementInfo.getCreditAccount(),
            settlementInfo.getCreditAccountType(),
            payment.getAmount()
        );
        
        printReconciliationResult(result);
        
        // Assertions
        assertEquals("VOSTRO", settlementInfo.getDebitAccountType(), "Debit should be from VOSTRO");
        assertEquals("CUSTOMER", settlementInfo.getCreditAccountType(), "Credit should be to CUSTOMER");
        assertTrue(result.isValid(), "Reconciliation should be valid");
        assertTrue(result.getErrors().isEmpty(), "Should have no errors");
    }
    
    /**
     * Test 2: SWIFT Inbound - Beneficiary is External Bank (routed via SWIFT)
     * Expected: Debit VOSTRO, Credit SWIFT_NOSTRO
     */
    @Test
    public void testSwiftInboundToExternalBank() {
        System.out.println("\n=== Test 2: SWIFT Inbound to External Bank (SWIFT) ===");
        
        // Create payment: SBI (India) -> Deutsche Bank (Germany)
        PaymentEvent payment = createPaymentEvent(
            "E2E-TEST-002",
            "SBININBB", "IN",  // Debtor: SBI
            "DEUTDEFF", "DE",  // Creditor: Deutsche Bank
            new BigDecimal("10000.00"),
            "USD",
            RoutingNetwork.SWIFT
        );
        
        PaymentPostingService.SettlementAccountInfo settlementInfo = paymentPostingService.determineSettlementAccountsPublic(payment);
        
        System.out.println("Debit Account: " + settlementInfo.getDebitAccount() + " (" + settlementInfo.getDebitAccountType() + ")");
        System.out.println("Credit Account: " + settlementInfo.getCreditAccount() + " (" + settlementInfo.getCreditAccountType() + ")");
        System.out.println("Amount: " + payment.getAmount() + " " + payment.getCurrency());
        
        ReconciliationValidator.ReconciliationResult result = reconciliationValidator.validateReconciliation(
            payment,
            settlementInfo.getDebitAccount(),
            settlementInfo.getDebitAccountType(),
            settlementInfo.getCreditAccount(),
            settlementInfo.getCreditAccountType(),
            payment.getAmount()
        );
        
        printReconciliationResult(result);
        
        assertEquals("VOSTRO", settlementInfo.getDebitAccountType());
        assertEquals("SWIFT_NOSTRO", settlementInfo.getCreditAccountType());
        assertTrue(result.isValid());
    }
    
    /**
     * Test 3: FED Inbound - Beneficiary is External Bank (routed via FED)
     * Expected: Debit VOSTRO, Credit FED
     */
    @Test
    public void testFedInboundToExternalBank() {
        System.out.println("\n=== Test 3: FED Inbound to External Bank ===");
        
        // Create payment: HSBC (UK) -> Chase (US) via FED
        PaymentEvent payment = createPaymentEvent(
            "E2E-TEST-003",
            "HSBCGB2L", "GB",  // Debtor: HSBC
            "CHASUS33", "US",  // Creditor: Chase
            new BigDecimal("15000.00"),
            "USD",
            RoutingNetwork.FED
        );
        
        PaymentPostingService.SettlementAccountInfo settlementInfo = paymentPostingService.determineSettlementAccountsPublic(payment);
        
        System.out.println("Debit Account: " + settlementInfo.getDebitAccount() + " (" + settlementInfo.getDebitAccountType() + ")");
        System.out.println("Credit Account: " + settlementInfo.getCreditAccount() + " (" + settlementInfo.getCreditAccountType() + ")");
        System.out.println("Amount: " + payment.getAmount() + " " + payment.getCurrency());
        
        ReconciliationValidator.ReconciliationResult result = reconciliationValidator.validateReconciliation(
            payment,
            settlementInfo.getDebitAccount(),
            settlementInfo.getDebitAccountType(),
            settlementInfo.getCreditAccount(),
            settlementInfo.getCreditAccountType(),
            payment.getAmount()
        );
        
        printReconciliationResult(result);
        
        assertEquals("VOSTRO", settlementInfo.getDebitAccountType());
        assertEquals("FED", settlementInfo.getCreditAccountType());
        assertTrue(result.isValid());
    }
    
    /**
     * Test 4: CHIPS Inbound - Beneficiary is External Bank (routed via CHIPS)
     * Expected: Debit VOSTRO, Credit CHIPS_NOSTRO
     */
    @Test
    public void testChipsInboundToExternalBank() {
        System.out.println("\n=== Test 4: CHIPS Inbound to External Bank ===");
        
        // Create payment: Deutsche Bank -> Chase via CHIPS
        PaymentEvent payment = createPaymentEvent(
            "E2E-TEST-004",
            "DEUTDEFF", "DE",  // Debtor: Deutsche Bank
            "CHASUS33", "US",  // Creditor: Chase
            new BigDecimal("20000.00"),
            "USD",
            RoutingNetwork.CHIPS
        );
        
        PaymentPostingService.SettlementAccountInfo settlementInfo = paymentPostingService.determineSettlementAccountsPublic(payment);
        
        System.out.println("Debit Account: " + settlementInfo.getDebitAccount() + " (" + settlementInfo.getDebitAccountType() + ")");
        System.out.println("Credit Account: " + settlementInfo.getCreditAccount() + " (" + settlementInfo.getCreditAccountType() + ")");
        System.out.println("Amount: " + payment.getAmount() + " " + payment.getCurrency());
        
        ReconciliationValidator.ReconciliationResult result = reconciliationValidator.validateReconciliation(
            payment,
            settlementInfo.getDebitAccount(),
            settlementInfo.getDebitAccountType(),
            settlementInfo.getCreditAccount(),
            settlementInfo.getCreditAccountType(),
            payment.getAmount()
        );
        
        printReconciliationResult(result);
        
        assertEquals("VOSTRO", settlementInfo.getDebitAccountType());
        assertEquals("CHIPS_NOSTRO", settlementInfo.getCreditAccountType());
        assertTrue(result.isValid());
    }
    
    /**
     * Test 5: Wells-Initiated Outbound - FED
     * Expected: Debit CUSTOMER, Credit FED
     */
    @Test
    public void testWellsOutboundFed() {
        System.out.println("\n=== Test 5: Wells-Initiated Outbound via FED ===");
        
        // Create payment: Wells Customer -> Chase via FED
        PaymentEvent payment = createPaymentEvent(
            "E2E-TEST-005",
            "WFBIUS6S", "US",  // Debtor: Wells Fargo
            "CHASUS33", "US",  // Creditor: Chase
            new BigDecimal("25000.00"),
            "USD",
            RoutingNetwork.FED
        );
        
        PaymentPostingService.SettlementAccountInfo settlementInfo = paymentPostingService.determineSettlementAccountsPublic(payment);
        
        System.out.println("Debit Account: " + settlementInfo.getDebitAccount() + " (" + settlementInfo.getDebitAccountType() + ")");
        System.out.println("Credit Account: " + settlementInfo.getCreditAccount() + " (" + settlementInfo.getCreditAccountType() + ")");
        System.out.println("Amount: " + payment.getAmount() + " " + payment.getCurrency());
        
        ReconciliationValidator.ReconciliationResult result = reconciliationValidator.validateReconciliation(
            payment,
            settlementInfo.getDebitAccount(),
            settlementInfo.getDebitAccountType(),
            settlementInfo.getCreditAccount(),
            settlementInfo.getCreditAccountType(),
            payment.getAmount()
        );
        
        printReconciliationResult(result);
        
        assertEquals("CUSTOMER", settlementInfo.getDebitAccountType());
        assertEquals("FED", settlementInfo.getCreditAccountType());
        assertTrue(result.isValid());
    }
    
    /**
     * Test 6: Wells-Initiated Outbound - CHIPS
     * Expected: Debit CUSTOMER, Credit CHIPS_NOSTRO
     */
    @Test
    public void testWellsOutboundChips() {
        System.out.println("\n=== Test 6: Wells-Initiated Outbound via CHIPS ===");
        
        // Create payment: Wells Customer -> Bank of America via CHIPS
        PaymentEvent payment = createPaymentEvent(
            "E2E-TEST-006",
            "WFBIUS6S", "US",  // Debtor: Wells Fargo
            "BOFAUS3N", "US",  // Creditor: Bank of America
            new BigDecimal("30000.00"),
            "USD",
            RoutingNetwork.CHIPS
        );
        
        PaymentPostingService.SettlementAccountInfo settlementInfo = paymentPostingService.determineSettlementAccountsPublic(payment);
        
        System.out.println("Debit Account: " + settlementInfo.getDebitAccount() + " (" + settlementInfo.getDebitAccountType() + ")");
        System.out.println("Credit Account: " + settlementInfo.getCreditAccount() + " (" + settlementInfo.getCreditAccountType() + ")");
        System.out.println("Amount: " + payment.getAmount() + " " + payment.getCurrency());
        
        ReconciliationValidator.ReconciliationResult result = reconciliationValidator.validateReconciliation(
            payment,
            settlementInfo.getDebitAccount(),
            settlementInfo.getDebitAccountType(),
            settlementInfo.getCreditAccount(),
            settlementInfo.getCreditAccountType(),
            payment.getAmount()
        );
        
        printReconciliationResult(result);
        
        assertEquals("CUSTOMER", settlementInfo.getDebitAccountType());
        assertEquals("CHIPS_NOSTRO", settlementInfo.getCreditAccountType());
        assertTrue(result.isValid());
    }
    
    /**
     * Test 7: Wells-Initiated Outbound - SWIFT
     * Expected: Debit CUSTOMER, Credit SWIFT_NOSTRO
     */
    @Test
    public void testWellsOutboundSwift() {
        System.out.println("\n=== Test 7: Wells-Initiated Outbound via SWIFT ===");
        
        // Create payment: Wells Customer -> HDFC Bank (India) via SWIFT
        PaymentEvent payment = createPaymentEvent(
            "E2E-TEST-007",
            "WFBIUS6S", "US",  // Debtor: Wells Fargo
            "HDFCINBB", "IN",  // Creditor: HDFC Bank
            new BigDecimal("35000.00"),
            "USD",
            RoutingNetwork.SWIFT
        );
        
        PaymentPostingService.SettlementAccountInfo settlementInfo = paymentPostingService.determineSettlementAccountsPublic(payment);
        
        System.out.println("Debit Account: " + settlementInfo.getDebitAccount() + " (" + settlementInfo.getDebitAccountType() + ")");
        System.out.println("Credit Account: " + settlementInfo.getCreditAccount() + " (" + settlementInfo.getCreditAccountType() + ")");
        System.out.println("Amount: " + payment.getAmount() + " " + payment.getCurrency());
        
        ReconciliationValidator.ReconciliationResult result = reconciliationValidator.validateReconciliation(
            payment,
            settlementInfo.getDebitAccount(),
            settlementInfo.getDebitAccountType(),
            settlementInfo.getCreditAccount(),
            settlementInfo.getCreditAccountType(),
            payment.getAmount()
        );
        
        printReconciliationResult(result);
        
        assertEquals("CUSTOMER", settlementInfo.getDebitAccountType());
        assertEquals("SWIFT_NOSTRO", settlementInfo.getCreditAccountType());
        assertTrue(result.isValid());
    }
    
    /**
     * Helper method to create a PaymentEvent for testing.
     */
    private PaymentEvent createPaymentEvent(
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
            .instructionId("INST-" + endToEndId)
            .transactionId("TXN-" + endToEndId)
            .debtorAgent(debtorAgent)
            .creditorAgent(creditorAgent)
            .amount(amount)
            .currency(currency)
            .routingContext(routingContext)
            .build();
    }
    
    /**
     * Print reconciliation result.
     */
    private void printReconciliationResult(ReconciliationValidator.ReconciliationResult result) {
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
        System.out.println();
    }
    
}

