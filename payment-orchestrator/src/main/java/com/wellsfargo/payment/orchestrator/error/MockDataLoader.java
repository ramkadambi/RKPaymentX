package com.wellsfargo.payment.orchestrator.error;

import com.wellsfargo.payment.canonical.Agent;
import com.wellsfargo.payment.canonical.EnrichmentContext;
import com.wellsfargo.payment.canonical.Party;
import com.wellsfargo.payment.canonical.PaymentEvent;
import com.wellsfargo.payment.canonical.ServiceResult;
import com.wellsfargo.payment.canonical.enums.MessageSource;
import com.wellsfargo.payment.canonical.enums.PaymentDirection;
import com.wellsfargo.payment.canonical.enums.ServiceResultStatus;
import com.wellsfargo.payment.error.ErrorRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mock Data Loader for Error Management UI Testing.
 * 
 * Populates the error store with sample error records for testing
 * the Error Management UI without requiring Kafka.
 */
public class MockDataLoader {
    
    private static final Logger log = LoggerFactory.getLogger(MockDataLoader.class);
    
    /**
     * Load mock error data into the error store.
     */
    public static void loadMockData(ErrorManagementService errorManagementService) {
        log.info("Loading mock error data for UI testing...");
        
        // Error 1: Account Validation Failure - Can be fixed and resumed
        createAccountValidationError(errorManagementService);
        
        // Error 2: Sanctions Check Failure - Needs to be cancelled (PACS.004)
        createSanctionsCheckError(errorManagementService);
        
        // Error 3: Balance Check Failure - Can be restarted from beginning
        createBalanceCheckError(errorManagementService);
        
        // Error 4: Payment Posting Failure - Can be fixed and resumed
        createPaymentPostingError(errorManagementService);
        
        // Error 5: Routing Validation Failure - Needs to be cancelled (PACS.004)
        createRoutingValidationError(errorManagementService);
        
        log.info("Mock error data loaded successfully. {} errors available for testing.", 
            errorManagementService.getAllErrors().size());
    }
    
    /**
     * Create account validation error - fixable and resumable.
     */
    private static void createAccountValidationError(ErrorManagementService service) {
        String endToEndId = "INV-ACC-VAL-001";
        PaymentEvent paymentEvent = createPaymentEvent(endToEndId, "25000.00", "Account Validation Payment");
        
        ServiceResult serviceResult = ServiceResult.builder()
            .endToEndId(endToEndId)
            .serviceName("account_validation")
            .status(ServiceResultStatus.FAIL)
            .errorMessage("Account number format invalid. Expected format: US-XXXXX-ACC#####")
            .build();
        
        ErrorRecord error = ErrorRecord.builder()
            .errorId(UUID.randomUUID().toString())
            .endToEndId(endToEndId)
            .paymentEvent(paymentEvent)
            .serviceResult(serviceResult)
            .serviceName("account_validation")
            .errorTopic("service.errors.account_validation")
            .errorTimestamp(Instant.now().minusSeconds(3600).toString())
            .lastSuccessfulStep("NONE")
            .severity(ErrorRecord.ErrorSeverity.MEDIUM)
            .category(ErrorRecord.ErrorCategory.BUSINESS)
            .status(ErrorRecord.ErrorStatus.NEW)
            .createdAt(Instant.now().minusSeconds(3600).toString())
            .updatedAt(Instant.now().minusSeconds(3600).toString())
            .errorContext("Account validation failed due to incorrect account number format")
            .build();
        
        service.addErrorDirectly(error);
    }
    
    /**
     * Create sanctions check error - should be cancelled with PACS.004.
     */
    private static void createSanctionsCheckError(ErrorManagementService service) {
        String endToEndId = "INV-SANCTIONS-001";
        PaymentEvent paymentEvent = createPaymentEvent(endToEndId, "75000.00", "Sanctions Check Payment");
        
        // Add enrichment context showing sanctions check failed
        Map<String, Object> sanctionsCheck = new HashMap<>();
        sanctionsCheck.put("status", "FAIL");
        sanctionsCheck.put("check_timestamp", Instant.now().minusSeconds(1800).toString());
        sanctionsCheck.put("match_found", true);
        sanctionsCheck.put("match_details", Map.of("list", "OFAC", "reason", "Sanctioned entity match"));
        
        EnrichmentContext enrichment = EnrichmentContext.builder()
            .sanctionsCheck(sanctionsCheck)
            .build();
        
        PaymentEvent enrichedEvent = PaymentEvent.builder()
            .msgId(paymentEvent.getMsgId())
            .endToEndId(paymentEvent.getEndToEndId())
            .transactionId(paymentEvent.getTransactionId())
            .amount(paymentEvent.getAmount())
            .currency(paymentEvent.getCurrency())
            .direction(paymentEvent.getDirection())
            .sourceMessageType(paymentEvent.getSourceMessageType())
            .sourceMessageRaw(paymentEvent.getSourceMessageRaw())
            .debtorAgent(paymentEvent.getDebtorAgent())
            .creditorAgent(paymentEvent.getCreditorAgent())
            .debtor(paymentEvent.getDebtor())
            .creditor(paymentEvent.getCreditor())
            .remittanceInfo(paymentEvent.getRemittanceInfo())
            .valueDate(paymentEvent.getValueDate())
            .enrichmentContext(enrichment)
            .routingContext(paymentEvent.getRoutingContext())
            .build();
        
        ServiceResult serviceResult = ServiceResult.builder()
            .endToEndId(endToEndId)
            .serviceName("sanctions_check")
            .status(ServiceResultStatus.FAIL)
            .errorMessage("Sanctions check failed: Match found in OFAC list. Payment blocked.")
            .build();
        
        ErrorRecord error = ErrorRecord.builder()
            .errorId(UUID.randomUUID().toString())
            .endToEndId(endToEndId)
            .paymentEvent(enrichedEvent)
            .serviceResult(serviceResult)
            .serviceName("sanctions_check")
            .errorTopic("service.errors.sanctions_check")
            .errorTimestamp(Instant.now().minusSeconds(1800).toString())
            .lastSuccessfulStep("routing_validation")
            .severity(ErrorRecord.ErrorSeverity.CRITICAL)
            .category(ErrorRecord.ErrorCategory.BUSINESS)
            .status(ErrorRecord.ErrorStatus.NEW)
            .createdAt(Instant.now().minusSeconds(1800).toString())
            .updatedAt(Instant.now().minusSeconds(1800).toString())
            .errorContext("Sanctions check failed - OFAC match detected. Payment must be cancelled and returned.")
            .build();
        
        service.addErrorDirectly(error);
    }
    
    /**
     * Create balance check error - can be restarted from beginning.
     */
    private static void createBalanceCheckError(ErrorManagementService service) {
        String endToEndId = "INV-BALANCE-001";
        PaymentEvent paymentEvent = createPaymentEvent(endToEndId, "100000.00", "Balance Check Payment");
        
        ServiceResult serviceResult = ServiceResult.builder()
            .endToEndId(endToEndId)
            .serviceName("balance_check")
            .status(ServiceResultStatus.FAIL)
            .errorMessage("Insufficient funds in debtor account. Available: $50,000.00, Required: $100,000.00")
            .build();
        
        ErrorRecord error = ErrorRecord.builder()
            .errorId(UUID.randomUUID().toString())
            .endToEndId(endToEndId)
            .paymentEvent(paymentEvent)
            .serviceResult(serviceResult)
            .serviceName("balance_check")
            .errorTopic("service.errors.balance_check")
            .errorTimestamp(Instant.now().minusSeconds(2400).toString())
            .lastSuccessfulStep("sanctions_check")
            .severity(ErrorRecord.ErrorSeverity.HIGH)
            .category(ErrorRecord.ErrorCategory.BUSINESS)
            .status(ErrorRecord.ErrorStatus.NEW)
            .createdAt(Instant.now().minusSeconds(2400).toString())
            .updatedAt(Instant.now().minusSeconds(2400).toString())
            .errorContext("Balance check failed - insufficient funds. Customer may need to deposit funds before retry.")
            .build();
        
        service.addErrorDirectly(error);
    }
    
    /**
     * Create payment posting error - fixable and resumable.
     */
    private static void createPaymentPostingError(ErrorManagementService service) {
        String endToEndId = "INV-POSTING-001";
        PaymentEvent paymentEvent = createPaymentEvent(endToEndId, "30000.00", "Payment Posting Payment");
        
        ServiceResult serviceResult = ServiceResult.builder()
            .endToEndId(endToEndId)
            .serviceName("payment_posting")
            .status(ServiceResultStatus.ERROR)
            .errorMessage("Database connection timeout during payment posting. Retry may succeed.")
            .build();
        
        ErrorRecord error = ErrorRecord.builder()
            .errorId(UUID.randomUUID().toString())
            .endToEndId(endToEndId)
            .paymentEvent(paymentEvent)
            .serviceResult(serviceResult)
            .serviceName("payment_posting")
            .errorTopic("service.errors.payment_posting")
            .errorTimestamp(Instant.now().minusSeconds(900).toString())
            .lastSuccessfulStep("balance_check")
            .severity(ErrorRecord.ErrorSeverity.MEDIUM)
            .category(ErrorRecord.ErrorCategory.TECHNICAL)
            .status(ErrorRecord.ErrorStatus.NEW)
            .createdAt(Instant.now().minusSeconds(900).toString())
            .updatedAt(Instant.now().minusSeconds(900).toString())
            .errorContext("Technical error during payment posting - database timeout. System may recover.")
            .build();
        
        service.addErrorDirectly(error);
    }
    
    /**
     * Create routing validation error - should be cancelled with PACS.004.
     */
    private static void createRoutingValidationError(ErrorManagementService service) {
        String endToEndId = "INV-ROUTING-001";
        PaymentEvent paymentEvent = createPaymentEvent(endToEndId, "50000.00", "Routing Validation Payment");
        
        ServiceResult serviceResult = ServiceResult.builder()
            .endToEndId(endToEndId)
            .serviceName("routing_validation")
            .status(ServiceResultStatus.FAIL)
            .errorMessage("Invalid BIC code for creditor agent. BIC 'INVALIDBICXXX' not found in SWIFT directory.")
            .build();
        
        ErrorRecord error = ErrorRecord.builder()
            .errorId(UUID.randomUUID().toString())
            .endToEndId(endToEndId)
            .paymentEvent(paymentEvent)
            .serviceResult(serviceResult)
            .serviceName("routing_validation")
            .errorTopic("service.errors.routing_validation")
            .errorTimestamp(Instant.now().minusSeconds(3000).toString())
            .lastSuccessfulStep("account_validation")
            .severity(ErrorRecord.ErrorSeverity.HIGH)
            .category(ErrorRecord.ErrorCategory.BUSINESS)
            .status(ErrorRecord.ErrorStatus.NEW)
            .createdAt(Instant.now().minusSeconds(3000).toString())
            .updatedAt(Instant.now().minusSeconds(3000).toString())
            .errorContext("Routing validation failed - invalid BIC code. Payment cannot be routed. Should be cancelled.")
            .build();
        
        service.addErrorDirectly(error);
    }
    
    /**
     * Create a sample payment event.
     */
    private static PaymentEvent createPaymentEvent(String endToEndId, String amount, String remittanceInfo) {
        return PaymentEvent.builder()
            .msgId("HDFC-" + endToEndId)
            .endToEndId(endToEndId)
            .transactionId("WF-TXN-" + UUID.randomUUID().toString().substring(0, 8))
            .amount(new BigDecimal(amount))
            .currency("USD")
            .direction(PaymentDirection.INBOUND)
            .sourceMessageType(MessageSource.ISO20022_PACS008)
            .sourceMessageRaw("<?xml version=\"1.0\"?><Document>...</Document>")
            .debtorAgent(Agent.builder()
                .idScheme("BIC")
                .idValue("HDFCINBBXXX")
                .name("HDFC Bank")
                .country("IN")
                .build())
            .creditorAgent(Agent.builder()
                .idScheme("BIC")
                .idValue("WFBIUS6SXXX")
                .name("Wells Fargo Bank")
                .country("US")
                .build())
            .debtor(Party.builder()
                .name("Customer A (India)")
                .accountId("IND-CUSTA-ACC12345")
                .build())
            .creditor(Party.builder()
                .name("Wells Fargo Customer B")
                .accountId("US-CUSTB-ACC67890")
                .build())
            .remittanceInfo(remittanceInfo)
            .valueDate(Instant.now().toString())
            .build();
    }
}

