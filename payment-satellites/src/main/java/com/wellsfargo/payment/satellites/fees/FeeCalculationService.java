package com.wellsfargo.payment.satellites.fees;

import com.wellsfargo.payment.canonical.PaymentEvent;
import com.wellsfargo.payment.canonical.enums.RoutingNetwork;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Service to calculate fees for payment processing.
 * 
 * Determines:
 * - Fee amount (default or negotiated)
 * - Whether fee should be deducted from principal (based on charge bearer code)
 * - Fee settlement method (deducted vs billing)
 */
@Slf4j
@Service
public class FeeCalculationService {
    
    @Autowired
    private FeeLookupService feeLookupService;
    
    /**
     * Calculate fee for a payment.
     * 
     * @param event PaymentEvent
     * @param network Routing network (FED, CHIPS, SWIFT)
     * @param isCorrespondent Whether this is a correspondent processing fee
     * @return FeeCalculationResult with fee details
     */
    public FeeCalculationResult calculateFee(PaymentEvent event, RoutingNetwork network, boolean isCorrespondent) {
        // Determine fee type
        String direction = event.getDirection() != null ? event.getDirection().getValue().toLowerCase() : "outbound";
        String networkStr = network != null ? network.getValue() : "SWIFT";
        String feeType = feeLookupService.determineFeeType(direction, networkStr, isCorrespondent);
        
        // Get bank BIC for negotiated fee lookup
        String bankBic = null;
        if (isCorrespondent) {
            // For correspondent processing, use creditor bank BIC
            bankBic = event.getCreditorAgent() != null ? event.getCreditorAgent().getIdValue() : null;
        } else {
            // For regular fees:
            // - Inbound: Fee is charged to the sending bank (debtor agent)
            // - Outbound: Fee is charged to the receiving bank (creditor agent)
            if ("inbound".equals(direction)) {
                bankBic = event.getDebtorAgent() != null ? event.getDebtorAgent().getIdValue() : null;
            } else {
                bankBic = event.getCreditorAgent() != null ? event.getCreditorAgent().getIdValue() : null;
            }
        }
        
        // Lookup fee
        FeeLookupService.FeeLookupResult feeLookup = feeLookupService.lookupFee(feeType, bankBic);
        
        // Get charge bearer code
        String chargeBearer = event.getChargeBearer();
        if (chargeBearer == null || chargeBearer.trim().isEmpty()) {
            chargeBearer = "SHA"; // Default to shared if not specified
        }
        
        // Determine fee application rules
        boolean deductFromPrincipal = shouldDeductFromPrincipal(chargeBearer);
        String feeSettlement = deductFromPrincipal ? "DEDUCTED" : "BILLING";
        
        return FeeCalculationResult.builder()
            .feeAmount(feeLookup.getFeeAmount())
            .isNegotiatedFee(feeLookup.isNegotiatedFee())
            .feeType(feeType)
            .chargeBearer(chargeBearer)
            .deductFromPrincipal(deductFromPrincipal)
            .feeSettlement(feeSettlement)
            .bankBic(bankBic)
            .build();
    }
    
    /**
     * Determine if fee should be deducted from principal based on charge bearer code.
     */
    private boolean shouldDeductFromPrincipal(String chargeBearer) {
        if (chargeBearer == null || chargeBearer.trim().isEmpty()) {
            return true; // Default to deduct if not specified
        }
        
        String code = chargeBearer.trim().toUpperCase();
        
        // OUR/DEBT: Sender pays - fees not deducted from principal
        if ("OUR".equals(code) || "DEBT".equals(code)) {
            return false;
        }
        
        // SHA/SHAR/CRED: Fees deducted from principal
        return true;
    }
    
    /**
     * Calculate net amount after fee deduction.
     * 
     * @param originalAmount Original payment amount
     * @param feeAmount Fee amount to deduct
     * @param deductFromPrincipal Whether to deduct from principal
     * @return Net amount (original amount minus fee if deductFromPrincipal is true)
     */
    public BigDecimal calculateNetAmount(BigDecimal originalAmount, BigDecimal feeAmount, boolean deductFromPrincipal) {
        if (deductFromPrincipal) {
            return originalAmount.subtract(feeAmount);
        }
        return originalAmount; // Fee billed separately, principal unchanged
    }
    
    @Data
    @lombok.Builder
    public static class FeeCalculationResult {
        private BigDecimal feeAmount;
        private boolean isNegotiatedFee;
        private String feeType;
        private String chargeBearer;
        private boolean deductFromPrincipal;
        private String feeSettlement; // DEDUCTED or BILLING
        private String bankBic;
    }
}

