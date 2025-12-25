package com.wellsfargo.payment.ingress.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wellsfargo.payment.canonical.*;
import com.wellsfargo.payment.canonical.enums.*;
import com.wellsfargo.payment.ingress.common.Iso20022MessageType;
import com.wellsfargo.payment.ingress.common.MessageTypeDetector;
import com.wellsfargo.payment.ingress.swift.Pacs008Mapper;
import com.wellsfargo.payment.ingress.swift.Pacs009Mapper;
import com.wellsfargo.payment.rules.RoutingRulesEngine;
import com.wellsfargo.payment.rules.RoutingRulesLoader;
import com.wellsfargo.payment.rules.RoutingRulesConfig;
import com.wellsfargo.payment.rules.RuleEvaluationResult;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;

/**
 * End-to-End Payment Flow Test - No Kafka
 * 
 * Runs all services from ingress to egress without Kafka.
 * Saves input and output messages for each step in folders.
 */
public class EndToEndFlowTest {
    
    private static final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule());
    
    private static final String OUTPUT_BASE_DIR = "test-output";
    
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println(" End-to-End Payment Flow Test");
        System.out.println(" No Kafka - All Services in Sequence");
        System.out.println("========================================");
        System.out.println();
        
        if (args.length == 0) {
            System.err.println("Usage: java EndToEndFlowTest <xml-file-path>");
            System.exit(1);
        }
        
        String xmlPath = args[0];
        String endToEndId = null;
        
        try {
            // Create output directory structure
            createOutputDirectories();
            
            // Step 1: Ingress
            System.out.println("Step 1: Ingress - Processing XML...");
            PaymentEvent paymentEvent = processIngress(xmlPath);
            endToEndId = paymentEvent.getEndToEndId();
            saveMessage("01_ingress", "input.xml", readXmlFile(xmlPath));
            saveMessage("01_ingress", "output_payment_event.json", paymentEvent);
            System.out.println("  [OK] PaymentEvent created: " + endToEndId);
            System.out.println();
            
            // Step 2: Account Validation
            System.out.println("Step 2: Account Validation...");
            PaymentEvent accountValidatedEvent = processAccountValidation(paymentEvent);
            saveMessage("02_account_validation", "input_payment_event.json", paymentEvent);
            saveMessage("02_account_validation", "output_enriched_payment_event.json", accountValidatedEvent);
            System.out.println("  [OK] Account validated and enriched");
            System.out.println();
            
            // Step 3: Routing Validation
            System.out.println("Step 3: Routing Validation...");
            PaymentEvent routedEvent = processRoutingValidation(accountValidatedEvent);
            saveMessage("03_routing_validation", "input_payment_event.json", accountValidatedEvent);
            saveMessage("03_routing_validation", "output_routed_payment_event.json", routedEvent);
            System.out.println("  [OK] Routing determined: " + 
                (routedEvent.getRoutingContext() != null && routedEvent.getRoutingContext().getSelectedNetwork() != null 
                    ? routedEvent.getRoutingContext().getSelectedNetwork().getValue() : "NONE"));
            System.out.println();
            
            // Step 4: Sanctions Check
            System.out.println("Step 4: Sanctions Check...");
            ServiceResult sanctionsResult = processSanctionsCheck(routedEvent);
            saveMessage("04_sanctions_check", "input_payment_event.json", routedEvent);
            saveMessage("04_sanctions_check", "output_service_result.json", sanctionsResult);
            System.out.println("  [OK] Sanctions check: " + sanctionsResult.getStatus());
            System.out.println();
            
            if (sanctionsResult.getStatus() != ServiceResultStatus.PASS) {
                System.out.println("[ERROR] Sanctions check failed - stopping flow");
                System.exit(1);
            }
            
            // Step 5: Balance Check
            System.out.println("Step 5: Balance Check...");
            ServiceResult balanceResult = processBalanceCheck(routedEvent);
            saveMessage("05_balance_check", "input_payment_event.json", routedEvent);
            saveMessage("05_balance_check", "output_service_result.json", balanceResult);
            System.out.println("  [OK] Balance check: " + balanceResult.getStatus());
            System.out.println();
            
            if (balanceResult.getStatus() != ServiceResultStatus.PASS) {
                System.out.println("[ERROR] Balance check failed - stopping flow");
                System.exit(1);
            }
            
            // Step 6: Payment Posting
            System.out.println("Step 6: Payment Posting...");
            ServiceResult postingResult = processPaymentPosting(routedEvent);
            saveMessage("06_payment_posting", "input_payment_event.json", routedEvent);
            saveMessage("06_payment_posting", "output_service_result.json", postingResult);
            System.out.println("  [OK] Payment posted: " + postingResult.getStatus());
            System.out.println();
            
            // Step 7: Final Status
            System.out.println("Step 7: Final Status...");
            PaymentEvent finalEvent = copyPaymentEventWithStatus(routedEvent, PaymentStatus.SETTLED);
            saveMessage("07_final_status", "final_payment_event.json", finalEvent);
            System.out.println("  [OK] Final status: SETTLED");
            System.out.println();
            
            // Step 8: Egress (mock - generate output message)
            System.out.println("Step 8: Egress - Generating Output Message...");
            String egressMessage = generateEgressMessage(finalEvent);
            saveMessage("08_egress", "input_payment_event.json", finalEvent);
            saveMessage("08_egress", "output_message.json", egressMessage);
            System.out.println("  [OK] Egress message generated");
            System.out.println();
            
            // Summary
            System.out.println("========================================");
            System.out.println(" Test Summary");
            System.out.println("========================================");
            System.out.println();
            System.out.println("EndToEndId: " + endToEndId);
            System.out.println("Status: SETTLED");
            System.out.println("Output Directory: " + new File(OUTPUT_BASE_DIR).getAbsolutePath());
            System.out.println();
            System.out.println("[SUCCESS] End-to-end flow completed!");
            
        } catch (Exception e) {
            System.err.println("[ERROR] Test failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void createOutputDirectories() throws IOException {
        String[] steps = {
            "01_ingress",
            "02_account_validation",
            "03_routing_validation",
            "04_sanctions_check",
            "05_balance_check",
            "06_payment_posting",
            "07_final_status",
            "08_egress"
        };
        
        for (String step : steps) {
            Files.createDirectories(Paths.get(OUTPUT_BASE_DIR, step));
        }
    }
    
    private static String readXmlFile(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)));
    }
    
    private static PaymentEvent processIngress(String xmlPath) throws Exception {
        String xmlContent = readXmlFile(xmlPath);
        Iso20022MessageType messageType = MessageTypeDetector.detect(xmlContent);
        if (messageType == Iso20022MessageType.PACS_008) {
            return Pacs008Mapper.mapToPaymentEvent(xmlContent, PaymentDirection.OUTBOUND);
        } else if (messageType == Iso20022MessageType.PACS_009) {
            // PACS.009 is typically INBOUND for settlement messages
            return Pacs009Mapper.mapToPaymentEvent(xmlContent, PaymentDirection.INBOUND);
        } else {
            throw new IllegalArgumentException("Unsupported message type: " + messageType);
        }
    }
    
    private static PaymentEvent processAccountValidation(PaymentEvent event) {
        // Mock account validation - enrich with account data
        String creditorBic = event.getCreditorAgent() != null ? event.getCreditorAgent().getIdValue() : null;
        
        if (creditorBic == null || creditorBic.trim().isEmpty()) {
            return event; // No enrichment possible
        }
        
        // Extract BIC8 (first 8 characters)
        String bic8 = creditorBic.length() >= 8 ? creditorBic.substring(0, 8) : creditorBic;
        
        // Mock account lookup - Wells Fargo
        Map<String, Object> accountValidation = new HashMap<>();
        boolean fedMember = false;
        boolean chipsMember = false;
        
        if ("WFBIUS6S".equals(bic8)) {
            accountValidation.put("status", ServiceResultStatus.PASS.getValue());
            accountValidation.put("creditor_type", "BANK");
            fedMember = true;
            chipsMember = true;
            accountValidation.put("fed_member", fedMember);
            accountValidation.put("chips_member", chipsMember);
            accountValidation.put("nostro_accounts_available", true);
            accountValidation.put("vostro_with_us", true);
            accountValidation.put("preferred_correspondent", "WFBIUS6SXXX");
        } else if ("CHASUS33".equals(bic8) || "BOFAUS3N".equals(bic8)) {
            // US Banks - both FED and CHIPS enabled
            accountValidation.put("status", ServiceResultStatus.PASS.getValue());
            accountValidation.put("creditor_type", "BANK");
            fedMember = true;
            chipsMember = true;
            accountValidation.put("fed_member", fedMember);
            accountValidation.put("chips_member", chipsMember);
            accountValidation.put("nostro_accounts_available", true);
            accountValidation.put("vostro_with_us", true);
        } else {
            // Default - foreign bank
            accountValidation.put("status", ServiceResultStatus.PASS.getValue());
            accountValidation.put("creditor_type", "BANK");
            fedMember = false;
            chipsMember = false;
            accountValidation.put("fed_member", fedMember);
            accountValidation.put("chips_member", chipsMember);
            accountValidation.put("nostro_accounts_available", false);
            accountValidation.put("vostro_with_us", false);
        }
        
        // Build bicLookup for routing rules (required for next_bank.chips_id_exists and next_bank.aba_exists)
        Map<String, Object> bicLookup = new HashMap<>();
        bicLookup.put("bic", creditorBic);
        bicLookup.put("country", event.getCreditorAgent() != null ? event.getCreditorAgent().getCountry() : "US");
        bicLookup.put("chips_member", chipsMember);
        bicLookup.put("fed_member", fedMember);
        // Routing rules check for chips_uid and aba_routing existence
        bicLookup.put("chips_uid", chipsMember ? "CHIPS-" + bic8 : null);
        bicLookup.put("aba_routing", fedMember ? "ABA-" + bic8 : null);
        
        EnrichmentContext enrichmentContext = EnrichmentContext.builder()
            .accountValidation(accountValidation)
            .bicLookup(bicLookup)
            .build();
        
        return copyPaymentEventWithEnrichment(event, enrichmentContext);
    }
    
    private static PaymentEvent processRoutingValidation(PaymentEvent event) throws Exception {
        // Use routing rules engine
        RoutingRulesLoader loader = new RoutingRulesLoader();
        RoutingRulesConfig config = loader.loadRules();
        RoutingRulesEngine rulesEngine = new RoutingRulesEngine(config);
        RuleEvaluationResult ruleResult = rulesEngine.evaluate(event);
        
        // Build routing context
        RoutingContext routingContext = RoutingContext.builder()
            .selectedNetwork(ruleResult.getSelectedNetwork())
            .routingRuleApplied(ruleResult.getAppliedRuleId())
            .agentChain(null) // Agent chain is built during routing, not from rule result
            .routingTrace(ruleResult.getRoutingTrace())
            .build();
        
        return copyPaymentEventWithRouting(event, routingContext);
    }
    
    private static ServiceResult processSanctionsCheck(PaymentEvent event) {
        // Mock sanctions check - always pass for Wells Fargo internal transfers
        String creditorBic = event.getCreditorAgent() != null ? event.getCreditorAgent().getIdValue() : null;
        String debtorBic = event.getDebtorAgent() != null ? event.getDebtorAgent().getIdValue() : null;
        
        // If both are Wells Fargo, always pass
        if ("WFBIUS6SXXX".equals(creditorBic) && "WFBIUS6SXXX".equals(debtorBic)) {
            return ServiceResult.builder()
                .endToEndId(event.getEndToEndId())
                .serviceName("sanctions_check")
                .status(ServiceResultStatus.PASS)
                .processingTimestamp(Instant.now().toString())
                .build();
        }
        
        // Otherwise, use mock sanctions check logic
        // For this test, we'll always pass
        return ServiceResult.builder()
            .endToEndId(event.getEndToEndId())
            .serviceName("sanctions_check")
            .status(ServiceResultStatus.PASS)
            .processingTimestamp(Instant.now().toString())
            .build();
    }
    
    private static ServiceResult processBalanceCheck(PaymentEvent event) {
        // Mock balance check - always pass for test
        return ServiceResult.builder()
            .endToEndId(event.getEndToEndId())
            .serviceName("balance_check")
            .status(ServiceResultStatus.PASS)
            .processingTimestamp(Instant.now().toString())
            .build();
    }
    
    private static ServiceResult processPaymentPosting(PaymentEvent event) {
        // Mock payment posting - always pass for test
        return ServiceResult.builder()
            .endToEndId(event.getEndToEndId())
            .serviceName("payment_posting")
            .status(ServiceResultStatus.PASS)
            .processingTimestamp(Instant.now().toString())
            .build();
    }
    
    private static String generateEgressMessage(PaymentEvent event) throws Exception {
        // Mock egress - generate a summary JSON
        Map<String, Object> egressData = new HashMap<>();
        egressData.put("endToEndId", event.getEndToEndId());
        egressData.put("status", event.getStatus().getValue());
        egressData.put("amount", event.getAmount());
        egressData.put("currency", event.getCurrency());
        if (event.getRoutingContext() != null && event.getRoutingContext().getSelectedNetwork() != null) {
            egressData.put("network", event.getRoutingContext().getSelectedNetwork().getValue());
        }
        egressData.put("timestamp", Instant.now().toString());
        
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(egressData);
    }
    
    private static PaymentEvent copyPaymentEventWithEnrichment(PaymentEvent event, EnrichmentContext enrichmentContext) {
        return PaymentEvent.builder()
            .msgId(event.getMsgId())
            .endToEndId(event.getEndToEndId())
            .transactionId(event.getTransactionId())
            .amount(event.getAmount())
            .currency(event.getCurrency())
            .direction(event.getDirection())
            .sourceMessageType(event.getSourceMessageType())
            .sourceMessageRaw(event.getSourceMessageRaw())
            .debtorAgent(event.getDebtorAgent())
            .creditorAgent(event.getCreditorAgent())
            .debtor(event.getDebtor())
            .creditor(event.getCreditor())
            .status(event.getStatus())
            .valueDate(event.getValueDate())
            .settlementDate(event.getSettlementDate())
            .remittanceInfo(event.getRemittanceInfo())
            .purposeCode(event.getPurposeCode())
            .chargeBearer(event.getChargeBearer())
            .enrichmentContext(enrichmentContext)
            .routingContext(event.getRoutingContext())
            .createdTimestamp(event.getCreatedTimestamp())
            .lastUpdatedTimestamp(Instant.now().toString())
            .processingState(event.getProcessingState())
            .metadata(event.getMetadata() != null ? new HashMap<>(event.getMetadata()) : new HashMap<>())
            .build();
    }
    
    private static PaymentEvent copyPaymentEventWithRouting(PaymentEvent event, RoutingContext routingContext) {
        return PaymentEvent.builder()
            .msgId(event.getMsgId())
            .endToEndId(event.getEndToEndId())
            .transactionId(event.getTransactionId())
            .amount(event.getAmount())
            .currency(event.getCurrency())
            .direction(event.getDirection())
            .sourceMessageType(event.getSourceMessageType())
            .sourceMessageRaw(event.getSourceMessageRaw())
            .debtorAgent(event.getDebtorAgent())
            .creditorAgent(event.getCreditorAgent())
            .debtor(event.getDebtor())
            .creditor(event.getCreditor())
            .status(event.getStatus())
            .valueDate(event.getValueDate())
            .settlementDate(event.getSettlementDate())
            .remittanceInfo(event.getRemittanceInfo())
            .purposeCode(event.getPurposeCode())
            .chargeBearer(event.getChargeBearer())
            .enrichmentContext(event.getEnrichmentContext())
            .routingContext(routingContext)
            .createdTimestamp(event.getCreatedTimestamp())
            .lastUpdatedTimestamp(Instant.now().toString())
            .processingState(event.getProcessingState())
            .metadata(event.getMetadata() != null ? new HashMap<>(event.getMetadata()) : new HashMap<>())
            .build();
    }
    
    private static PaymentEvent copyPaymentEventWithStatus(PaymentEvent event, PaymentStatus status) {
        return PaymentEvent.builder()
            .msgId(event.getMsgId())
            .endToEndId(event.getEndToEndId())
            .transactionId(event.getTransactionId())
            .amount(event.getAmount())
            .currency(event.getCurrency())
            .direction(event.getDirection())
            .sourceMessageType(event.getSourceMessageType())
            .sourceMessageRaw(event.getSourceMessageRaw())
            .debtorAgent(event.getDebtorAgent())
            .creditorAgent(event.getCreditorAgent())
            .debtor(event.getDebtor())
            .creditor(event.getCreditor())
            .status(status)
            .valueDate(event.getValueDate())
            .settlementDate(event.getSettlementDate())
            .remittanceInfo(event.getRemittanceInfo())
            .purposeCode(event.getPurposeCode())
            .chargeBearer(event.getChargeBearer())
            .enrichmentContext(event.getEnrichmentContext())
            .routingContext(event.getRoutingContext())
            .createdTimestamp(event.getCreatedTimestamp())
            .lastUpdatedTimestamp(Instant.now().toString())
            .processingState(event.getProcessingState())
            .metadata(event.getMetadata() != null ? new HashMap<>(event.getMetadata()) : new HashMap<>())
            .build();
    }
    
    private static void saveMessage(String step, String filename, Object message) throws IOException {
        String filePath = Paths.get(OUTPUT_BASE_DIR, step, filename).toString();
        File file = new File(filePath);
        
        if (message instanceof String) {
            Files.write(file.toPath(), ((String) message).getBytes());
        } else {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(message);
            Files.write(file.toPath(), json.getBytes());
        }
        
        System.out.println("    Saved: " + filePath);
    }
}

