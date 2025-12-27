package com.wellsfargo.payment.satellites.fees;

import com.wellsfargo.payment.canonical.PaymentEvent;
import com.wellsfargo.payment.canonical.enums.RoutingNetwork;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Service to apply fees to PaymentEvent and calculate settlement amounts.
 * 
 * This service:
 * 1. Calculates fees based on routing network and bank relationships
 * 2. Determines if fees should be deducted from principal (based on charge bearer)
 * 3. Calculates settlement amount (IntrBkSttlmAmt) after fee deductions
 * 4. Preserves instructed amount (InstdAmt) - original amount from customer
 * 5. Enriches PaymentEvent with fee information
 */
@Slf4j
@Service
public class FeeApplicationService {
    
    @Autowired
    private FeeCalculationService feeCalculationService;
    
    /**
     * Apply fees to PaymentEvent and calculate settlement amount.
     * 
     * This method:
     * - Preserves the original amount (InstdAmt) in PaymentEvent.amount
     * - Calculates settlement amount (IntrBkSttlmAmt) after fee deductions
     * - Stores fee information in enrichment context
     * 
     * @param event PaymentEvent to apply fees to
     * @return PaymentEvent with fees applied and settlement amount calculated
     */
    public PaymentEvent applyFees(PaymentEvent event) {
        if (event == null || event.getAmount() == null) {
            log.warn("Cannot apply fees: PaymentEvent or amount is null");
            return event;
        }
        
        // Get routing network
        RoutingNetwork network = event.getRoutingContext() != null 
            ? event.getRoutingContext().getSelectedNetwork() 
            : null;
        
        if (network == null) {
            log.debug("No routing network selected, skipping fee calculation for E2E={}", event.getEndToEndId());
            // If no network selected, settlement amount equals instructed amount
            // Build new PaymentEvent with settlementAmount set
            return PaymentEvent.builder()
                .msgId(event.getMsgId())
                .endToEndId(event.getEndToEndId())
                .transactionId(event.getTransactionId())
                .amount(event.getAmount())
                .settlementAmount(event.getAmount())  // Settlement equals instructed when no fees
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
                .routingContext(event.getRoutingContext())
                .createdTimestamp(event.getCreatedTimestamp())
                .lastUpdatedTimestamp(event.getLastUpdatedTimestamp())
                .processingState(event.getProcessingState())
                .metadata(event.getMetadata())
                .build();
        }
        
        // Check if correspondent processing is needed
        boolean isCorrespondent = event.getEnrichmentContext() != null &&
            event.getEnrichmentContext().getAccountValidation() != null &&
            Boolean.TRUE.equals(event.getEnrichmentContext().getAccountValidation().get("requires_correspondent"));
        
        // Calculate fee
        FeeCalculationService.FeeCalculationResult feeResult = feeCalculationService.calculateFee(
            event, network, isCorrespondent);
        
        // Preserve original amount (InstdAmt) - this never changes
        BigDecimal instructedAmount = event.getAmount();
        
        // Calculate settlement amount (IntrBkSttlmAmt) - after fee deductions
        BigDecimal settlementAmount = feeCalculationService.calculateNetAmount(
            instructedAmount, 
            feeResult.getFeeAmount(), 
            feeResult.isDeductFromPrincipal());
        
        // Build fee information for enrichment context
        Map<String, Object> feeInfo = new HashMap<>();
        feeInfo.put("fee_amount", feeResult.getFeeAmount().toString());
        feeInfo.put("fee_currency", event.getCurrency());
        feeInfo.put("is_negotiated_fee", feeResult.isNegotiatedFee());
        feeInfo.put("fee_type", feeResult.getFeeType());
        feeInfo.put("charge_bearer", feeResult.getChargeBearer());
        feeInfo.put("deduct_from_principal", feeResult.isDeductFromPrincipal());
        feeInfo.put("fee_settlement", feeResult.getFeeSettlement());
        feeInfo.put("instructed_amount", instructedAmount.toString());
        feeInfo.put("settlement_amount", settlementAmount.toString());
        feeInfo.put("bank_bic", feeResult.getBankBic());
        
        // Update enrichment context with fee information
        com.wellsfargo.payment.canonical.EnrichmentContext.EnrichmentContextBuilder enrichmentContextBuilder = 
            com.wellsfargo.payment.canonical.EnrichmentContext.builder();
        
        if (event.getEnrichmentContext() != null) {
            enrichmentContextBuilder
                .accountValidation(event.getEnrichmentContext().getAccountValidation())
                .bicLookup(event.getEnrichmentContext().getBicLookup())
                .abaLookup(event.getEnrichmentContext().getAbaLookup())
                .chipsLookup(event.getEnrichmentContext().getChipsLookup())
                .sanctionsCheck(event.getEnrichmentContext().getSanctionsCheck())
                .balanceCheck(event.getEnrichmentContext().getBalanceCheck())
                .settlementAccounts(event.getEnrichmentContext().getSettlementAccounts());
        }
        
        enrichmentContextBuilder.feeCalculation(feeInfo);
        
        // Build updated PaymentEvent (preserve all fields, update settlementAmount and enrichmentContext)
        PaymentEvent updatedEvent = PaymentEvent.builder()
            .msgId(event.getMsgId())
            .endToEndId(event.getEndToEndId())
            .transactionId(event.getTransactionId())
            .amount(event.getAmount())  // Preserve original amount (InstdAmt)
            .settlementAmount(settlementAmount)  // Set settlement amount (IntrBkSttlmAmt)
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
            .enrichmentContext(enrichmentContextBuilder.build())
            .routingContext(event.getRoutingContext())
            .createdTimestamp(event.getCreatedTimestamp())
            .lastUpdatedTimestamp(event.getLastUpdatedTimestamp())
            .processingState(event.getProcessingState())
            .metadata(event.getMetadata())
            .build();
        
        log.info("Fees applied for E2E={}: InstdAmt={}, IntrBkSttlmAmt={}, Fee={}, ChargeBearer={}, Deduct={}",
            event.getEndToEndId(),
            instructedAmount,
            settlementAmount,
            feeResult.getFeeAmount(),
            feeResult.getChargeBearer(),
            feeResult.isDeductFromPrincipal());
        
        return updatedEvent;
    }
}

