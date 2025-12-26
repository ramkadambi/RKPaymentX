package com.wellsfargo.payment.satellites.balancecheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.*;

/**
 * Settlement Account Lookup Service for Wells Fargo internal accounts.
 * 
 * Provides lookup functionality for:
 * - Vostro accounts: Foreign bank accounts maintained at Wells Fargo (for inbound payments)
 * - FED account: Wells Fargo's Federal Reserve settlement account
 * - CHIPS nostro accounts: CHIPS participant accounts maintained at Wells Fargo
 * - SWIFT nostro accounts: Foreign bank accounts maintained at Wells Fargo (for inbound/outbound payments)
 * 
 * Accounts are loaded from settlement_accounts.json for easy maintenance.
 * In production, this would query MongoDB or Wells Fargo's internal account management system.
 */
@Service
public class SettlementAccountLookupService {
    
    private static final Logger log = LoggerFactory.getLogger(SettlementAccountLookupService.class);
    private static final String SETTLEMENT_ACCOUNTS_FILE = "data/settlement_accounts.json";
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // Wells Fargo's FED settlement account (single account for all FED operations)
    private Map<String, String> fedAccount;
    
    // Vostro accounts: Foreign bank accounts maintained at Wells Fargo (for inbound payments)
    // Key: BIC8, Value: Account data map
    private Map<String, Map<String, Object>> vostroAccounts;
    
    // CHIPS nostro accounts: CHIPS participant accounts maintained at Wells Fargo
    // Key: BIC8, Value: Account data map
    private Map<String, Map<String, Object>> chipsNostroAccounts;
    
    // SWIFT nostro accounts: Foreign bank accounts maintained at Wells Fargo
    // Key: BIC8, Value: List of account data maps (multiple currencies per bank)
    private Map<String, List<Map<String, Object>>> swiftNostroAccounts;
    
    @PostConstruct
    public void init() {
        loadSettlementAccounts();
    }
    
    /**
     * Load settlement accounts from JSON file.
     */
    private void loadSettlementAccounts() {
        try {
            ClassPathResource resource = new ClassPathResource(SETTLEMENT_ACCOUNTS_FILE);
            if (!resource.exists()) {
                log.warn("Settlement accounts file not found: {}. Using empty accounts.", SETTLEMENT_ACCOUNTS_FILE);
                fedAccount = new HashMap<>();
                vostroAccounts = new HashMap<>();
                chipsNostroAccounts = new HashMap<>();
                swiftNostroAccounts = new HashMap<>();
                return;
            }
            
            try (InputStream is = resource.getInputStream()) {
                JsonNode root = objectMapper.readTree(is);
                
                // Load FED account
                JsonNode fedNode = root.get("fed_settlement_account");
                if (fedNode != null) {
                    fedAccount = new HashMap<>();
                    fedAccount.put("account_number", fedNode.get("account_number").asText());
                    fedAccount.put("account_name", fedNode.get("account_name").asText());
                    fedAccount.put("routing_number", fedNode.get("routing_number").asText());
                    fedAccount.put("currency", fedNode.get("currency").asText());
                    fedAccount.put("account_type", fedNode.get("account_type").asText());
                } else {
                    fedAccount = new HashMap<>();
                }
                
                // Load Vostro accounts
                JsonNode vostroNode = root.get("vostro_accounts");
                vostroAccounts = new HashMap<>();
                if (vostroNode != null && vostroNode.isObject()) {
                    vostroNode.fields().forEachRemaining(entry -> {
                        String bic = entry.getKey();
                        JsonNode accountNode = entry.getValue();
                        Map<String, Object> account = convertJsonNodeToMap(accountNode);
                        vostroAccounts.put(bic.toUpperCase(), account);
                    });
                }
                
                // Load CHIPS nostro accounts
                JsonNode chipsNode = root.get("chips_nostro_accounts");
                chipsNostroAccounts = new HashMap<>();
                if (chipsNode != null && chipsNode.isObject()) {
                    chipsNode.fields().forEachRemaining(entry -> {
                        String bic = entry.getKey();
                        JsonNode accountNode = entry.getValue();
                        Map<String, Object> account = convertJsonNodeToMap(accountNode);
                        chipsNostroAccounts.put(bic.toUpperCase(), account);
                    });
                }
                
                // Load SWIFT nostro accounts (can be array or single object)
                JsonNode swiftNode = root.get("swift_nostro_accounts");
                swiftNostroAccounts = new HashMap<>();
                if (swiftNode != null && swiftNode.isObject()) {
                    swiftNode.fields().forEachRemaining(entry -> {
                        String bic = entry.getKey();
                        JsonNode accountData = entry.getValue();
                        List<Map<String, Object>> accounts = new ArrayList<>();
                        
                        if (accountData.isArray()) {
                            // Multiple accounts (different currencies)
                            for (JsonNode accountNode : accountData) {
                                accounts.add(convertJsonNodeToMap(accountNode));
                            }
                        } else if (accountData.isObject()) {
                            // Single account
                            accounts.add(convertJsonNodeToMap(accountData));
                        }
                        
                        swiftNostroAccounts.put(bic.toUpperCase(), accounts);
                    });
                }
                
                log.info("Loaded settlement accounts: FED=1, Vostro={}, CHIPS={}, SWIFT={}", 
                    vostroAccounts.size(), chipsNostroAccounts.size(), swiftNostroAccounts.size());
            }
        } catch (Exception e) {
            log.error("Failed to load settlement accounts from {}", SETTLEMENT_ACCOUNTS_FILE, e);
            fedAccount = new HashMap<>();
            vostroAccounts = new HashMap<>();
            chipsNostroAccounts = new HashMap<>();
            swiftNostroAccounts = new HashMap<>();
        }
    }
    
    /**
     * Convert JsonNode to Map<String, Object>.
     */
    private Map<String, Object> convertJsonNodeToMap(JsonNode node) {
        Map<String, Object> map = new HashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if (value.isTextual()) {
                    map.put(key, value.asText());
                } else if (value.isNumber()) {
                    map.put(key, value.asDouble());
                } else if (value.isBoolean()) {
                    map.put(key, value.asBoolean());
                } else {
                    map.put(key, value.toString());
                }
            });
        }
        return map;
    }
    
    /**
     * Lookup vostro account for a foreign bank.
     * 
     * @param bic Foreign bank BIC code (BIC8 or BIC11 format)
     * @return Optional with account data or empty if not found
     */
    public Optional<Map<String, Object>> lookupVostroAccount(String bic) {
        if (bic == null || bic.trim().isEmpty()) {
            return Optional.empty();
        }
        
        // Normalize BIC to BIC8 (first 8 chars) for lookup
        String bic8 = bic.trim().toUpperCase();
        if (bic8.length() > 8) {
            bic8 = bic8.substring(0, 8);
        }
        
        Map<String, Object> account = vostroAccounts != null ? vostroAccounts.get(bic8) : null;
        return Optional.ofNullable(account != null ? new HashMap<>(account) : null);
    }
    
    /**
     * Lookup Wells Fargo's FED settlement account.
     * 
     * @return FED account data
     */
    public Map<String, String> lookupFedAccount() {
        return fedAccount != null ? new HashMap<>(fedAccount) : new HashMap<>();
    }
    
    /**
     * Lookup CHIPS nostro account for a CHIPS participant bank.
     * 
     * @param bic CHIPS participant bank BIC code (BIC8 or BIC11 format)
     * @return Optional with account data or empty if not found
     */
    public Optional<Map<String, Object>> lookupChipsNostroAccount(String bic) {
        if (bic == null || bic.trim().isEmpty()) {
            return Optional.empty();
        }
        
        // Normalize BIC to BIC8 (first 8 chars) for lookup
        String bic8 = bic.trim().toUpperCase();
        if (bic8.length() > 8) {
            bic8 = bic8.substring(0, 8);
        }
        
        Map<String, Object> account = chipsNostroAccounts != null ? chipsNostroAccounts.get(bic8) : null;
        return Optional.ofNullable(account != null ? new HashMap<>(account) : null);
    }
    
    /**
     * Lookup SWIFT nostro account for a foreign bank.
     * 
     * @param bic Foreign bank BIC code (BIC8 or BIC11 format)
     * @param currency Payment currency (optional, for currency-specific lookup)
     * @return Optional with account data or empty if not found
     */
    public Optional<Map<String, Object>> lookupSwiftNostroAccount(String bic, String currency) {
        if (bic == null || bic.trim().isEmpty()) {
            return Optional.empty();
        }
        
        // Normalize BIC to BIC8 (first 8 chars) for lookup
        String bic8 = bic.trim().toUpperCase();
        if (bic8.length() > 8) {
            bic8 = bic8.substring(0, 8);
        }
        
        List<Map<String, Object>> accounts = swiftNostroAccounts.get(bic8);
        if (accounts == null || accounts.isEmpty()) {
            return Optional.empty();
        }
        
        // If currency specified, try to find matching currency account
        if (currency != null && !currency.trim().isEmpty()) {
            String normalizedCurrency = currency.trim().toUpperCase();
            for (Map<String, Object> account : accounts) {
                String accountCurrency = (String) account.get("currency");
                if (normalizedCurrency.equals(accountCurrency)) {
                    return Optional.of(new HashMap<>(account));
                }
            }
        }
        
        // If no currency match or no currency specified, return first account (typically USD)
        return Optional.of(new HashMap<>(accounts.get(0)));
    }
    
    /**
     * Lookup SWIFT nostro account for a foreign bank (backward compatibility).
     * Uses USD as default currency.
     * 
     * @param bic Foreign bank BIC code (BIC8 or BIC11 format)
     * @return Optional with account data or empty if not found
     */
    public Optional<Map<String, Object>> lookupSwiftOutNostroAccount(String bic) {
        return lookupSwiftNostroAccount(bic, "USD");
    }
}

