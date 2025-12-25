package com.wellsfargo.payment.satellites.balancecheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Ledger Service for reading account balances from test ledger.
 * 
 * This is a read-only service for balance checking.
 * Does NOT introduce persistence - only reads from test_ledger.json.
 * 
 * In production, this would query MongoDB or Redis for account balances.
 */
@Service
public class LedgerService {
    
    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);
    private static final String LEDGER_FILE = "data/test_ledger.json";
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private Map<String, BigDecimal> accountBalances = new HashMap<>();
    
    @PostConstruct
    public void init() {
        loadLedger();
    }
    
    /**
     * Load ledger from classpath resource.
     */
    private void loadLedger() {
        try {
            ClassPathResource resource = new ClassPathResource(LEDGER_FILE);
            if (!resource.exists()) {
                log.warn("Ledger file not found: {}. Using empty ledger.", LEDGER_FILE);
                accountBalances = new HashMap<>();
                return;
            }
            
            try (InputStream is = resource.getInputStream()) {
                JsonNode root = objectMapper.readTree(is);
                JsonNode accounts = root.get("accounts");
                
                if (accounts != null && accounts.isObject()) {
                    accounts.fields().forEachRemaining(entry -> {
                        String accountId = entry.getKey();
                        JsonNode account = entry.getValue();
                        JsonNode balance = account.get("current_balance");
                        
                        if (balance != null) {
                            BigDecimal balanceValue;
                            if (balance.isTextual()) {
                                balanceValue = new BigDecimal(balance.asText());
                            } else if (balance.isNumber()) {
                                balanceValue = balance.decimalValue();
                            } else {
                                log.warn("Invalid balance format for account: {}", accountId);
                                return;
                            }
                            accountBalances.put(accountId, balanceValue);
                        }
                    });
                }
                
                log.info("Loaded {} accounts from ledger", accountBalances.size());
            }
        } catch (Exception e) {
            log.error("Failed to load ledger from {}", LEDGER_FILE, e);
            accountBalances = new HashMap<>();
        }
    }
    
    /**
     * Get current balance for an account (read-only).
     * 
     * @param accountId Account identifier (e.g., "WF-VOSTRO-SBI-USD-001")
     * @return Current balance as BigDecimal, or empty if account doesn't exist
     */
    public Optional<BigDecimal> getAccountBalance(String accountId) {
        if (accountId == null || accountId.trim().isEmpty()) {
            return Optional.empty();
        }
        
        BigDecimal balance = accountBalances.get(accountId.trim());
        return Optional.ofNullable(balance);
    }
    
    /**
     * Check if account has sufficient balance for a debit (read-only).
     * 
     * @param accountId Account identifier
     * @param amount Amount to debit
     * @return True if account exists and has sufficient balance, False otherwise
     */
    public boolean hasSufficientBalance(String accountId, BigDecimal amount) {
        if (accountId == null || amount == null) {
            return false;
        }
        
        Optional<BigDecimal> balanceOpt = getAccountBalance(accountId);
        if (balanceOpt.isEmpty()) {
            return false;
        }
        
        BigDecimal balance = balanceOpt.get();
        return balance.compareTo(amount) >= 0;
    }
    
    /**
     * Check if account exists in ledger.
     * 
     * @param accountId Account identifier
     * @return True if account exists, False otherwise
     */
    public boolean accountExists(String accountId) {
        if (accountId == null || accountId.trim().isEmpty()) {
            return false;
        }
        
        return accountBalances.containsKey(accountId.trim());
    }
}

