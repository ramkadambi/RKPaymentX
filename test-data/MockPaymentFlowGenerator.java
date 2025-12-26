package com.wellsfargo.payment.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import com.wellsfargo.payment.canonical.Agent;
import com.wellsfargo.payment.canonical.EnrichmentContext;
import com.wellsfargo.payment.canonical.Party;
import com.wellsfargo.payment.canonical.PaymentEvent;
import com.wellsfargo.payment.canonical.RoutingContext;
import com.wellsfargo.payment.canonical.enums.MessageSource;
import com.wellsfargo.payment.canonical.enums.Pacs002Status;
import com.wellsfargo.payment.canonical.enums.PaymentDirection;
import com.wellsfargo.payment.canonical.enums.RoutingNetwork;
import com.wellsfargo.payment.notification.Pacs002Generator;

import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Mock Payment Flow Generator.
 * 
 * Simulates a complete payment flow from HDFC to Wells Fargo without Kafka.
 * Generates:
 * 1. Input PACS.008 message
 * 2. PaymentEvent at each step
 * 3. PACS.002 status messages for each processing step
 * 4. Final output
 * 
 * All files are saved to sample/ directory.
 */
public class MockPaymentFlowGenerator {
    
    private static final String SAMPLE_DIR = "sample";
    private static final String END_TO_END_ID = "INV-HDFC-WF-20251225-001";
    private static final String MSG_ID = "HDFC-20251225-001";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_INSTANT;
    
    private final Pacs002Generator pacs002Generator = new Pacs002Generator();
    private final ObjectMapper objectMapper;
    private final Path sampleDir;
    
    public MockPaymentFlowGenerator() throws IOException {
        this.sampleDir = Paths.get(SAMPLE_DIR);
        if (!Files.exists(sampleDir)) {
            Files.createDirectories(sampleDir);
        }
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }
    
    public static void main(String[] args) {
        try {
            MockPaymentFlowGenerator generator = new MockPaymentFlowGenerator();
            generator.generateCompleteFlow();
            System.out.println("✓ Payment flow simulation completed successfully!");
            System.out.println("✓ All files saved to: " + SAMPLE_DIR + "/");
        } catch (Exception e) {
            System.err.println("Error generating payment flow: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    /**
     * Generate complete payment flow.
     */
    public void generateCompleteFlow() throws IOException {
        System.out.println("Generating complete payment flow...");
        
        // Step 1: Create input PACS.008
        PaymentEvent paymentEvent = createInputPacs008();
        savePaymentEvent(paymentEvent, "01_input_pacs008_payment_event.json");
        
        // Step 2: RCVD - Payment received
        generatePacs002(paymentEvent, Pacs002Status.RCVD, "RCVD", 
            "Payment received and acknowledged", "02_status_RCVD.pacs.002.xml");
        
        // Step 3: Account Validation (ACCP)
        PaymentEvent enrichedEvent = enrichForAccountValidation(paymentEvent);
        savePaymentEvent(enrichedEvent, "03_account_validation_enriched.json");
        generatePacs002(enrichedEvent, Pacs002Status.ACCP, "ACCP", 
            "Payment accepted for processing after account validation", 
            "04_status_ACCP.pacs.002.xml");
        
        // Step 4: Routing Validation (ACCC)
        PaymentEvent routedEvent = enrichForRoutingValidation(enrichedEvent);
        savePaymentEvent(routedEvent, "05_routing_validation_routed.json");
        generatePacs002(routedEvent, Pacs002Status.ACCC, "ACCC", 
            "Payment accepted after customer profile validation (KYC/AML checks passed)", 
            "06_status_ACCC.pacs.002.xml");
        
        // Step 5: Sanctions Check (PDNG)
        PaymentEvent sanctionsEvent = enrichForSanctionsCheck(routedEvent);
        savePaymentEvent(sanctionsEvent, "07_sanctions_check_passed.json");
        generatePacs002(sanctionsEvent, Pacs002Status.PDNG, "PDNG", 
            "Payment pending further checks (sanctions passed)", 
            "08_status_PDNG.pacs.002.xml");
        
        // Step 6: Balance Check (ACSP)
        PaymentEvent balanceEvent = enrichForBalanceCheck(sanctionsEvent);
        savePaymentEvent(balanceEvent, "09_balance_check_passed.json");
        generatePacs002(balanceEvent, Pacs002Status.ACSP, "ACSP", 
            "Payment accepted, settlement in process", 
            "10_status_ACSP.pacs.002.xml");
        
        // Step 7: Payment Posting (ACSC)
        PaymentEvent postingEvent = enrichForPaymentPosting(balanceEvent);
        savePaymentEvent(postingEvent, "11_payment_posting_completed.json");
        generatePacs002(postingEvent, Pacs002Status.ACSC, "ACSC", 
            "Payment accepted, settlement completed (funds credited to beneficiary account)", 
            "12_status_ACSC.pacs.002.xml");
        
        // Step 8: Final Status
        PaymentEvent finalEvent = createFinalStatus(postingEvent);
        savePaymentEvent(finalEvent, "13_final_status.json");
        
        // Generate summary
        generateSummary();
        
        System.out.println("\n✓ Generated files:");
        System.out.println("  - Input PACS.008 payment event");
        System.out.println("  - 6 PACS.002 status messages (RCVD, ACCP, ACCC, PDNG, ACSP, ACSC)");
        System.out.println("  - Payment events at each processing step");
        System.out.println("  - Final status");
    }
    
    /**
     * Create input PACS.008 payment event.
     */
    private PaymentEvent createInputPacs008() {
        return PaymentEvent.builder()
            .msgId(MSG_ID)
            .endToEndId(END_TO_END_ID)
            .transactionId("WF-TXN-" + Instant.now().toString().replaceAll("[^0-9]", "").substring(0, 14))
            .amount(new BigDecimal("50000.00"))
            .currency("USD")
            .direction(PaymentDirection.INBOUND)
            .sourceMessageType(MessageSource.ISO20022_PACS008)
            .sourceMessageRaw(generatePacs008Xml())
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
            .remittanceInfo("Payment for Invoice INV-2025-001")
            .valueDate(Instant.now().toString())
            .build();
    }
    
    /**
     * Generate PACS.008 XML for source message.
     */
    private String generatePacs008Xml() {
        String timestamp = Instant.now().toString();
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08\">\n" +
            "  <FIToFICstmrCdtTrf>\n" +
            "    <GrpHdr>\n" +
            "      <MsgId>" + MSG_ID + "</MsgId>\n" +
            "      <CreDtTm>" + timestamp + "</CreDtTm>\n" +
            "      <NbOfTxs>1</NbOfTxs>\n" +
            "      <SttlmInf>\n" +
            "        <SttlmMtd>CLRG</SttlmMtd>\n" +
            "      </SttlmInf>\n" +
            "    </GrpHdr>\n" +
            "    <CdtTrfTxInf>\n" +
            "      <PmtId>\n" +
            "        <InstrId>CUSTIND-A-20251225</InstrId>\n" +
            "        <EndToEndId>" + END_TO_END_ID + "</EndToEndId>\n" +
            "      </PmtId>\n" +
            "      <Amt>\n" +
            "        <InstdAmt Ccy=\"USD\">50000.00</InstdAmt>\n" +
            "      </Amt>\n" +
            "      <Dbtr>\n" +
            "        <Nm>Customer A (India)</Nm>\n" +
            "      </Dbtr>\n" +
            "      <DbtrAcct>\n" +
            "        <Id>\n" +
            "          <Othr>\n" +
            "            <Id>IND-CUSTA-ACC12345</Id>\n" +
            "          </Othr>\n" +
            "        </Id>\n" +
            "      </DbtrAcct>\n" +
            "      <DbtrAgt>\n" +
            "        <FinInstnId>\n" +
            "          <BIC>HDFCINBBXXX</BIC>\n" +
            "        </FinInstnId>\n" +
            "      </DbtrAgt>\n" +
            "      <CdtrAgt>\n" +
            "        <FinInstnId>\n" +
            "          <BIC>WFBIUS6SXXX</BIC>\n" +
            "        </FinInstnId>\n" +
            "      </CdtrAgt>\n" +
            "      <Cdtr>\n" +
            "        <Nm>Wells Fargo Customer B</Nm>\n" +
            "      </Cdtr>\n" +
            "      <CdtrAcct>\n" +
            "        <Id>\n" +
            "          <Othr>\n" +
            "            <Id>US-CUSTB-ACC67890</Id>\n" +
            "          </Othr>\n" +
            "        </Id>\n" +
            "      </CdtrAcct>\n" +
            "      <RmtInf>\n" +
            "        <Ustrd>Payment for Invoice INV-2025-001</Ustrd>\n" +
            "      </RmtInf>\n" +
            "    </CdtTrfTxInf>\n" +
            "  </FIToFICstmrCdtTrf>\n" +
            "</Document>";
    }
    
    /**
     * Enrich payment event after account validation.
     */
    private PaymentEvent enrichForAccountValidation(PaymentEvent event) {
        Map<String, Object> accountValidation = new HashMap<>();
        accountValidation.put("status", "PASS");
        accountValidation.put("creditor_type", "CUSTOMER");
        accountValidation.put("accountStatus", "ACTIVE");
        accountValidation.put("accountType", "CHECKING");
        accountValidation.put("validationTimestamp", Instant.now().toString());
        
        EnrichmentContext enrichment = EnrichmentContext.builder()
            .accountValidation(accountValidation)
            .build();
        
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
            .remittanceInfo(event.getRemittanceInfo())
            .valueDate(event.getValueDate())
            .enrichmentContext(enrichment)
            .routingContext(event.getRoutingContext())
            .build();
    }
    
    /**
     * Enrich payment event after routing validation.
     */
    private PaymentEvent enrichForRoutingValidation(PaymentEvent event) {
        Map<String, Object> routingDecision = new HashMap<>();
        routingDecision.put("selected_network", "SWIFT");
        routingDecision.put("routing_method", "DIRECT");
        routingDecision.put("routingTimestamp", Instant.now().toString());
        
        RoutingContext routing = RoutingContext.builder()
            .selectedNetwork(RoutingNetwork.SWIFT)
            .routingRuleApplied("RULE_SWIFT_DIRECT")
            .routingDecision(routingDecision)
            .build();
        
        Map<String, Object> accountValidation = event.getEnrichmentContext() != null && 
            event.getEnrichmentContext().getAccountValidation() != null ?
            new HashMap<>(event.getEnrichmentContext().getAccountValidation()) : new HashMap<>();
        accountValidation.put("kycStatus", "PASSED");
        accountValidation.put("amlStatus", "PASSED");
        accountValidation.put("customerProfileValidated", true);
        
        EnrichmentContext enrichment = EnrichmentContext.builder()
            .accountValidation(accountValidation)
            .build();
        
        return createEnrichedEvent(event, enrichment, routing);
    }
    
    /**
     * Enrich payment event after sanctions check.
     */
    private PaymentEvent enrichForSanctionsCheck(PaymentEvent event) {
        Map<String, Object> sanctionsCheck = new HashMap<>();
        sanctionsCheck.put("status", "PASS");
        sanctionsCheck.put("check_timestamp", Instant.now().toString());
        sanctionsCheck.put("match_found", false);
        sanctionsCheck.put("sanctionsListsChecked", "OFAC,UN,EU");
        
        EnrichmentContext existing = event.getEnrichmentContext();
        EnrichmentContext enrichment = EnrichmentContext.builder()
            .accountValidation(existing != null ? existing.getAccountValidation() : null)
            .sanctionsCheck(sanctionsCheck)
            .build();
        
        return event.toBuilder()
            .enrichmentContext(enrichment)
            .routingContext(event.getRoutingContext())
            .build();
    }
    
    /**
     * Enrich payment event after balance check.
     */
    private PaymentEvent enrichForBalanceCheck(PaymentEvent event) {
        Map<String, Object> balanceCheck = new HashMap<>();
        balanceCheck.put("status", "PASS");
        balanceCheck.put("account_id", "US-CUSTB-ACC67890");
        balanceCheck.put("available_balance", new BigDecimal("150000.00"));
        balanceCheck.put("required_amount", new BigDecimal("50000.00"));
        balanceCheck.put("sufficient_funds", true);
        
        EnrichmentContext existing = event.getEnrichmentContext();
        EnrichmentContext enrichment = EnrichmentContext.builder()
            .accountValidation(existing != null ? existing.getAccountValidation() : null)
            .sanctionsCheck(existing != null ? existing.getSanctionsCheck() : null)
            .balanceCheck(balanceCheck)
            .build();
        
        return event.toBuilder()
            .enrichmentContext(enrichment)
            .routingContext(event.getRoutingContext())
            .build();
    }
    
    /**
     * Enrich payment event after payment posting.
     */
    private PaymentEvent enrichForPaymentPosting(PaymentEvent event) {
        Map<String, Object> settlementAccounts = new HashMap<>();
        settlementAccounts.put("debit_account", "VOSTRO-HDFC-001");
        settlementAccounts.put("credit_account", "US-CUSTB-ACC67890");
        settlementAccounts.put("debit_account_type", "VOSTRO");
        settlementAccounts.put("credit_account_type", "CUSTOMER");
        settlementAccounts.put("postingTimestamp", Instant.now().toString());
        settlementAccounts.put("settlementDate", Instant.now().toString());
        settlementAccounts.put("creditorAccountCredited", true);
        
        EnrichmentContext existing = event.getEnrichmentContext();
        EnrichmentContext enrichment = EnrichmentContext.builder()
            .accountValidation(existing != null ? existing.getAccountValidation() : null)
            .sanctionsCheck(existing != null ? existing.getSanctionsCheck() : null)
            .balanceCheck(existing != null ? existing.getBalanceCheck() : null)
            .settlementAccounts(settlementAccounts)
            .build();
        
        PaymentEvent enriched = createEnrichedEvent(event, enrichment, event.getRoutingContext());
        return PaymentEvent.builder()
            .msgId(enriched.getMsgId())
            .endToEndId(enriched.getEndToEndId())
            .transactionId(enriched.getTransactionId())
            .amount(enriched.getAmount())
            .currency(enriched.getCurrency())
            .direction(enriched.getDirection())
            .sourceMessageType(enriched.getSourceMessageType())
            .sourceMessageRaw(enriched.getSourceMessageRaw())
            .debtorAgent(enriched.getDebtorAgent())
            .creditorAgent(enriched.getCreditorAgent())
            .debtor(enriched.getDebtor())
            .creditor(enriched.getCreditor())
            .remittanceInfo(enriched.getRemittanceInfo())
            .valueDate(enriched.getValueDate())
            .settlementDate(Instant.now().toString())
            .enrichmentContext(enriched.getEnrichmentContext())
            .routingContext(enriched.getRoutingContext())
            .build();
    }
    
    /**
     * Create final status payment event.
     */
    private PaymentEvent createFinalStatus(PaymentEvent event) {
        return event; // Final status is same as posting event
    }
    
    /**
     * Helper method to create enriched payment event.
     */
    private PaymentEvent createEnrichedEvent(PaymentEvent event, EnrichmentContext enrichment, RoutingContext routing) {
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
            .remittanceInfo(event.getRemittanceInfo())
            .valueDate(event.getValueDate())
            .enrichmentContext(enrichment)
            .routingContext(routing)
            .build();
    }
    
    /**
     * Generate and save PACS.002 status message.
     */
    private void generatePacs002(PaymentEvent event, Pacs002Status status, 
                                  String reasonCode, String additionalInfo, 
                                  String filename) throws IOException {
        String pacs002Xml = pacs002Generator.generatePacs002(event, status, reasonCode, additionalInfo);
        Path filePath = sampleDir.resolve(filename);
        try (FileWriter writer = new FileWriter(filePath.toFile())) {
            writer.write(pacs002Xml);
        }
        System.out.println("  Generated: " + filename);
    }
    
    /**
     * Save payment event as JSON.
     */
    private void savePaymentEvent(PaymentEvent event, String filename) throws IOException {
        String json = objectMapper.writeValueAsString(event);
        Path filePath = sampleDir.resolve(filename);
        Files.write(filePath, json.getBytes());
    }
    
    /**
     * Convert PaymentEvent to JSON (legacy method - not used, kept for reference).
     */
    @SuppressWarnings("unused")
    private String convertToJson(PaymentEvent event) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"msgId\": \"").append(event.getMsgId()).append("\",\n");
        json.append("  \"endToEndId\": \"").append(event.getEndToEndId()).append("\",\n");
        json.append("  \"transactionId\": \"").append(event.getTransactionId()).append("\",\n");
        json.append("  \"amount\": ").append(event.getAmount()).append(",\n");
        json.append("  \"currency\": \"").append(event.getCurrency()).append("\",\n");
        json.append("  \"direction\": \"").append(event.getDirection()).append("\",\n");
        json.append("  \"sourceMessageType\": \"").append(event.getSourceMessageType()).append("\",\n");
        json.append("  \"debtorAgent\": {\n");
        json.append("    \"idScheme\": \"").append(event.getDebtorAgent().getIdScheme()).append("\",\n");
        json.append("    \"idValue\": \"").append(event.getDebtorAgent().getIdValue()).append("\",\n");
        json.append("    \"name\": \"").append(event.getDebtorAgent().getName()).append("\",\n");
        json.append("    \"country\": \"").append(event.getDebtorAgent().getCountry()).append("\"\n");
        json.append("  },\n");
        json.append("  \"creditorAgent\": {\n");
        json.append("    \"idScheme\": \"").append(event.getCreditorAgent().getIdScheme()).append("\",\n");
        json.append("    \"idValue\": \"").append(event.getCreditorAgent().getIdValue()).append("\",\n");
        json.append("    \"name\": \"").append(event.getCreditorAgent().getName()).append("\",\n");
        json.append("    \"country\": \"").append(event.getCreditorAgent().getCountry()).append("\"\n");
        json.append("  },\n");
        json.append("  \"debtor\": {\n");
        json.append("    \"name\": \"").append(event.getDebtor().getName()).append("\",\n");
        json.append("    \"accountId\": \"").append(event.getDebtor().getAccountId()).append("\"\n");
        json.append("  },\n");
        json.append("  \"creditor\": {\n");
        json.append("    \"name\": \"").append(event.getCreditor().getName()).append("\",\n");
        json.append("    \"accountId\": \"").append(event.getCreditor().getAccountId()).append("\"\n");
        json.append("  },\n");
        json.append("  \"remittanceInfo\": \"").append(event.getRemittanceInfo() != null ? event.getRemittanceInfo() : "").append("\",\n");
        json.append("  \"valueDate\": \"").append(event.getValueDate() != null ? event.getValueDate() : "").append("\"");
        if (event.getEnrichmentContext() != null) {
            json.append(",\n  \"enrichmentContext\": ").append(convertEnrichmentContextToJson(event.getEnrichmentContext()));
        }
        if (event.getRoutingContext() != null) {
            json.append(",\n  \"routingContext\": ").append(convertRoutingContextToJson(event.getRoutingContext()));
        }
        json.append("\n}");
        return json.toString();
    }
    
    /**
     * Convert EnrichmentContext to JSON string.
     */
    private String convertEnrichmentContextToJson(EnrichmentContext ctx) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        boolean first = true;
        if (ctx.getAccountValidation() != null) {
            json.append("    \"accountValidation\": ").append(convertMapToJson(ctx.getAccountValidation()));
            first = false;
        }
        if (ctx.getSanctionsCheck() != null) {
            if (!first) json.append(",\n");
            json.append("    \"sanctionsCheck\": ").append(convertMapToJson(ctx.getSanctionsCheck()));
            first = false;
        }
        if (ctx.getBalanceCheck() != null) {
            if (!first) json.append(",\n");
            json.append("    \"balanceCheck\": ").append(convertMapToJson(ctx.getBalanceCheck()));
            first = false;
        }
        if (ctx.getSettlementAccounts() != null) {
            if (!first) json.append(",\n");
            json.append("    \"settlementAccounts\": ").append(convertMapToJson(ctx.getSettlementAccounts()));
        }
        json.append("\n  }");
        return json.toString();
    }
    
    /**
     * Convert RoutingContext to JSON string.
     */
    private String convertRoutingContextToJson(RoutingContext ctx) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("    \"selectedNetwork\": \"").append(ctx.getSelectedNetwork() != null ? ctx.getSelectedNetwork() : "").append("\",\n");
        json.append("    \"routingRuleApplied\": \"").append(ctx.getRoutingRuleApplied() != null ? ctx.getRoutingRuleApplied() : "").append("\"");
        if (ctx.getRoutingDecision() != null) {
            json.append(",\n    \"routingDecision\": ").append(convertMapToJson(ctx.getRoutingDecision()));
        }
        json.append("\n  }");
        return json.toString();
    }
    
    /**
     * Convert Map to JSON string.
     */
    private String convertMapToJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) json.append(",\n");
            json.append("      \"").append(entry.getKey()).append("\": ");
            Object value = entry.getValue();
            if (value instanceof String) {
                json.append("\"").append(escapeJson(value.toString())).append("\"");
            } else if (value instanceof Boolean || value instanceof Number) {
                json.append(value);
            } else {
                json.append("\"").append(escapeJson(String.valueOf(value))).append("\"");
            }
            first = false;
        }
        json.append("\n    }");
        return json.toString();
    }
    
    /**
     * Escape JSON special characters.
     */
    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
    
    /**
     * Generate summary document.
     */
    private void generateSummary() throws IOException {
        String summary = "# Payment Flow Simulation Summary\n\n" +
            "## Payment Details\n" +
            "- **End-to-End ID**: " + END_TO_END_ID + "\n" +
            "- **Message ID**: " + MSG_ID + "\n" +
            "- **Amount**: USD 50,000.00\n" +
            "- **Debtor**: Customer A (India) - HDFC Bank\n" +
            "- **Creditor**: Wells Fargo Customer B - Wells Fargo Bank\n\n" +
            "## Processing Steps\n\n" +
            "1. **RCVD** - Payment received and acknowledged\n" +
            "2. **ACCP** - Accepted for processing (account validation passed)\n" +
            "3. **ACCC** - Accepted after customer profile validation (KYC/AML passed)\n" +
            "4. **PDNG** - Pending further checks (sanctions check passed)\n" +
            "5. **ACSP** - Accepted, settlement in process (balance check passed)\n" +
            "6. **ACSC** - Accepted, settlement completed (funds credited)\n\n" +
            "## Generated Files\n\n" +
            "### Input\n" +
            "- `01_input_pacs008_payment_event.json` - Initial payment event from PACS.008\n\n" +
            "### Processing Steps\n" +
            "- `03_account_validation_enriched.json` - After account validation\n" +
            "- `05_routing_validation_routed.json` - After routing validation\n" +
            "- `07_sanctions_check_passed.json` - After sanctions check\n" +
            "- `09_balance_check_passed.json` - After balance check\n" +
            "- `11_payment_posting_completed.json` - After payment posting\n" +
            "- `13_final_status.json` - Final status\n\n" +
            "### PACS.002 Status Messages\n" +
            "- `02_status_RCVD.pacs.002.xml` - Payment received\n" +
            "- `04_status_ACCP.pacs.002.xml` - Accepted for processing\n" +
            "- `06_status_ACCC.pacs.002.xml` - Accepted after customer validation\n" +
            "- `08_status_PDNG.pacs.002.xml` - Pending further checks\n" +
            "- `10_status_ACSP.pacs.002.xml` - Accepted, settlement in process\n" +
            "- `12_status_ACSC.pacs.002.xml` - Accepted, settlement completed\n\n" +
            "## Notes\n" +
            "- All processing steps completed successfully\n" +
            "- Payment was routed via SWIFT (direct)\n" +
            "- Sanctions checks passed (OFAC, UN, EU lists checked)\n" +
            "- Funds successfully credited to beneficiary account\n";
        
        Path filePath = sampleDir.resolve("SUMMARY.md");
        try (FileWriter writer = new FileWriter(filePath.toFile())) {
            writer.write(summary);
        }
    }
}

