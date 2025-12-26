package com.wellsfargo.payment.satellites.accountvalidation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Service to lookup US bank capabilities and correspondent relationships.
 * 
 * Loads data from bank_capabilities.json which mimics:
 * - Federal Reserve E-Payments Routing Directory (FED-enabled banks)
 * - CHIPS Participant Directory (CHIPS-enabled banks)
 * - SWIFT/Accuity Bankers Almanac (correspondent relationships)
 */
@Slf4j
@Component
public class BankCapabilitiesLookupService {
    
    private static final String BANK_CAPABILITIES_FILE = "data/bank_capabilities.json";
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private Map<String, BankCapability> bankCapabilities = new HashMap<>();
    
    @PostConstruct
    public void loadBankCapabilities() {
        try {
            ClassPathResource resource = new ClassPathResource(BANK_CAPABILITIES_FILE);
            if (!resource.exists()) {
                log.warn("Bank capabilities file not found: {}", BANK_CAPABILITIES_FILE);
                return;
            }
            
            try (InputStream inputStream = resource.getInputStream()) {
                JsonNode root = objectMapper.readTree(inputStream);
                JsonNode usBanks = root.path("us_banks");
                
                if (!usBanks.isObject()) {
                    log.warn("No us_banks found in bank_capabilities.json");
                    return;
                }
                
                usBanks.fields().forEachRemaining(entry -> {
                    String bic = entry.getKey();
                    JsonNode bankData = entry.getValue();
                    
                    try {
                        BankCapability capability = parseBankCapability(bic, bankData);
                        bankCapabilities.put(bic, capability);
                        log.debug("Loaded bank capability: {} - {}", bic, capability.getBankName());
                    } catch (Exception e) {
                        log.error("Error parsing bank capability for {}: {}", bic, e.getMessage());
                    }
                });
                
                log.info("Loaded {} bank capabilities from {}", bankCapabilities.size(), BANK_CAPABILITIES_FILE);
            }
        } catch (Exception e) {
            log.error("Error loading bank capabilities from {}: {}", BANK_CAPABILITIES_FILE, e.getMessage(), e);
        }
    }
    
    private BankCapability parseBankCapability(String bic, JsonNode bankData) {
        BankCapability capability = new BankCapability();
        capability.setBic(bic);
        capability.setBankName(bankData.path("bank_name").asText());
        capability.setBankCategory(bankData.path("bank_category").asText());
        capability.setChipsEnabled(bankData.path("chips_enabled").asBoolean(false));
        capability.setFedEnabled(bankData.path("fed_enabled").asBoolean(false));
        capability.setChipsUid(bankData.path("chips_uid").asText(null));
        capability.setAbaRouting(bankData.path("aba_routing").asText(null));
        capability.setCountry(bankData.path("country").asText("US"));
        capability.setState(bankData.path("state").asText(null));
        capability.setCity(bankData.path("city").asText(null));
        
        // Parse correspondent bank if present
        JsonNode correspondentNode = bankData.path("correspondent_bank");
        if (!correspondentNode.isMissingNode() && correspondentNode.isObject()) {
            CorrespondentBank correspondent = new CorrespondentBank();
            correspondent.setBic(correspondentNode.path("bic").asText());
            correspondent.setBankName(correspondentNode.path("bank_name").asText());
            correspondent.setFedEnabled(correspondentNode.path("fed_enabled").asBoolean(false));
            correspondent.setChipsEnabled(correspondentNode.path("chips_enabled").asBoolean(false));
            correspondent.setRelationshipType(correspondentNode.path("relationship_type").asText());
            correspondent.setSettlementMethod(correspondentNode.path("settlement_method").asText());
            capability.setCorrespondentBank(correspondent);
        }
        
        return capability;
    }
    
    /**
     * Lookup bank capability by BIC.
     * 
     * @param bic Bank BIC (BIC8 or BIC11 format)
     * @return Optional BankCapability or empty if not found
     */
    public Optional<BankCapability> lookupBankCapability(String bic) {
        if (bic == null || bic.trim().isEmpty()) {
            return Optional.empty();
        }
        
        // Normalize BIC to BIC8 (first 8 chars) for lookup
        String bic8 = bic.trim().toUpperCase();
        if (bic8.length() >= 8) {
            bic8 = bic8.substring(0, 8);
        }
        
        BankCapability capability = bankCapabilities.get(bic8);
        if (capability == null) {
            log.debug("Bank capability not found for BIC: {}", bic);
            return Optional.empty();
        }
        
        return Optional.of(capability);
    }
    
    /**
     * Check if bank requires correspondent for FED settlement.
     * 
     * @param bic Bank BIC
     * @return true if bank is non-FED-enabled and requires correspondent
     */
    public boolean requiresCorrespondent(String bic) {
        return lookupBankCapability(bic)
            .map(cap -> !cap.isFedEnabled() && cap.getCorrespondentBank() != null)
            .orElse(false);
    }
    
    /**
     * Get correspondent bank BIC for non-FED-enabled bank.
     * 
     * @param bic Bank BIC
     * @return Optional correspondent bank BIC or empty if not needed/not found
     */
    public Optional<String> getCorrespondentBankBic(String bic) {
        return lookupBankCapability(bic)
            .map(BankCapability::getCorrespondentBank)
            .map(CorrespondentBank::getBic);
    }
    
    @Data
    public static class BankCapability {
        private String bic;
        private String bankName;
        private String bankCategory; // CHIPS_PARTICIPANT, FED_ENABLED, NON_FED_ENABLED
        private boolean chipsEnabled;
        private boolean fedEnabled;
        private String chipsUid;
        private String abaRouting;
        private String country;
        private String state;
        private String city;
        private CorrespondentBank correspondentBank;
    }
    
    @Data
    public static class CorrespondentBank {
        private String bic;
        private String bankName;
        private boolean fedEnabled;
        private boolean chipsEnabled;
        private String relationshipType; // CORRESPONDENT
        private String settlementMethod; // FED
    }
}

