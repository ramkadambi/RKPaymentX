package com.wellsfargo.payment.satellites.fees;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Service to lookup fee schedules for payment processing.
 * 
 * Supports:
 * - Default fees (applicable to all banks)
 * - Negotiated fees (relationship-specific fees with specific banks)
 */
@Slf4j
@Component
public class FeeLookupService {
    
    private static final String FEE_SCHEDULES_FILE = "data/fee_schedules.json";
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private Map<String, BigDecimal> defaultFees = new HashMap<>();
    private Map<String, Map<String, BigDecimal>> negotiatedFees = new HashMap<>();
    
    @PostConstruct
    public void loadFeeSchedules() {
        try {
            ClassPathResource resource = new ClassPathResource(FEE_SCHEDULES_FILE);
            if (!resource.exists()) {
                log.warn("Fee schedules file not found: {}", FEE_SCHEDULES_FILE);
                return;
            }
            
            try (var inputStream = resource.getInputStream()) {
                JsonNode root = objectMapper.readTree(inputStream);
                
                // Load default fees
                JsonNode defaultFeesNode = root.path("default_fees").path("fees");
                defaultFeesNode.fields().forEachRemaining(entry -> {
                    String feeType = entry.getKey();
                    JsonNode feeData = entry.getValue();
                    String amount = feeData.path("amount").asText();
                    try {
                        defaultFees.put(feeType, new BigDecimal(amount));
                        log.debug("Loaded default fee: {} = {}", feeType, amount);
                    } catch (NumberFormatException e) {
                        log.error("Invalid fee amount for {}: {}", feeType, amount);
                    }
                });
                
                // Load negotiated fees
                JsonNode negotiatedFeesNode = root.path("negotiated_fees").path("fee_schedules");
                negotiatedFeesNode.fields().forEachRemaining(bankEntry -> {
                    String bankBic = bankEntry.getKey();
                    JsonNode bankSchedule = bankEntry.getValue();
                    JsonNode fees = bankSchedule.path("fees");
                    
                    Map<String, BigDecimal> bankFees = new HashMap<>();
                    fees.fields().forEachRemaining(feeEntry -> {
                        String feeType = feeEntry.getKey();
                        JsonNode feeData = feeEntry.getValue();
                        String amount = feeData.path("amount").asText();
                        try {
                            bankFees.put(feeType, new BigDecimal(amount));
                            log.debug("Loaded negotiated fee for {}: {} = {}", bankBic, feeType, amount);
                        } catch (NumberFormatException e) {
                            log.error("Invalid fee amount for {} / {}: {}", bankBic, feeType, amount);
                        }
                    });
                    
                    if (!bankFees.isEmpty()) {
                        negotiatedFees.put(bankBic, bankFees);
                    }
                });
                
                log.info("Loaded {} default fees and {} negotiated fee schedules from {}", 
                    defaultFees.size(), negotiatedFees.size(), FEE_SCHEDULES_FILE);
            }
        } catch (Exception e) {
            log.error("Error loading fee schedules from {}: {}", FEE_SCHEDULES_FILE, e.getMessage(), e);
        }
    }
    
    /**
     * Lookup fee amount for a specific fee type and bank.
     * 
     * @param feeType Fee type (e.g., "inbound_swift", "outbound_fed", "correspondent_processing")
     * @param bankBic Bank BIC (optional, for negotiated fees)
     * @return FeeLookupResult with fee amount and whether negotiated fee was used
     */
    public FeeLookupResult lookupFee(String feeType, String bankBic) {
        // First, check for negotiated fee
        if (bankBic != null && !bankBic.trim().isEmpty()) {
            String bic8 = normalizeBic(bankBic);
            Map<String, BigDecimal> bankFees = negotiatedFees.get(bic8);
            if (bankFees != null) {
                BigDecimal negotiatedFee = bankFees.get(feeType);
                if (negotiatedFee != null) {
                    log.debug("Using negotiated fee for {} / {}: {}", bic8, feeType, negotiatedFee);
                    return FeeLookupResult.builder()
                        .feeAmount(negotiatedFee)
                        .isNegotiatedFee(true)
                        .feeType(feeType)
                        .bankBic(bic8)
                        .build();
                }
            }
        }
        
        // Fall back to default fee
        BigDecimal defaultFee = defaultFees.get(feeType);
        if (defaultFee != null) {
            log.debug("Using default fee for {}: {}", feeType, defaultFee);
            return FeeLookupResult.builder()
                .feeAmount(defaultFee)
                .isNegotiatedFee(false)
                .feeType(feeType)
                .bankBic(bankBic)
                .build();
        }
        
        // No fee found
        log.warn("No fee found for feeType={}, bankBic={}", feeType, bankBic);
        return FeeLookupResult.builder()
            .feeAmount(BigDecimal.ZERO)
            .isNegotiatedFee(false)
            .feeType(feeType)
            .bankBic(bankBic)
            .build();
    }
    
    /**
     * Normalize BIC to BIC8 (first 8 characters).
     */
    private String normalizeBic(String bic) {
        if (bic == null || bic.trim().isEmpty()) {
            return bic;
        }
        String bic8 = bic.trim().toUpperCase();
        if (bic8.length() >= 8) {
            bic8 = bic8.substring(0, 8);
        }
        return bic8;
    }
    
    /**
     * Determine fee type based on payment direction and network.
     * 
     * @param direction Payment direction ("inbound" or "outbound")
     * @param network Routing network (FED, CHIPS, SWIFT)
     * @param isCorrespondent Whether this is a correspondent processing fee
     * @return Fee type string (e.g., "inbound_swift", "outbound_fed", "correspondent_processing")
     */
    public String determineFeeType(String direction, String network, boolean isCorrespondent) {
        if (isCorrespondent) {
            return "correspondent_processing";
        }
        
        // Normalize direction
        String normalizedDirection = direction != null ? direction.toLowerCase() : "outbound";
        if (!"inbound".equals(normalizedDirection) && !"outbound".equals(normalizedDirection)) {
            normalizedDirection = "outbound"; // Default to outbound if unknown
        }
        
        // Normalize network
        String normalizedNetwork = network != null ? network.toLowerCase() : "swift";
        
        return normalizedDirection + "_" + normalizedNetwork;
    }
    
    @Data
    @lombok.Builder
    public static class FeeLookupResult {
        private BigDecimal feeAmount;
        private boolean isNegotiatedFee;
        private String feeType;
        private String bankBic;
    }
}

