package com.wellsfargo.payment.satellites.balancecheck;

import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Settlement Account Lookup Service for Wells Fargo internal accounts.
 * 
 * Provides lookup functionality for:
 * - Vostro accounts: Foreign bank accounts maintained at Wells Fargo (for inbound payments)
 * - FED account: Wells Fargo's Federal Reserve settlement account
 * - CHIPS nostro accounts: CHIPS participant accounts maintained at Wells Fargo
 * - SWIFT out nostro accounts: Foreign bank accounts maintained at Wells Fargo (for outbound payments)
 * 
 * In production, this would query MongoDB or Wells Fargo's internal account management system.
 */
@Service
public class SettlementAccountLookupService {
    
    // Wells Fargo's FED settlement account (single account for all FED operations)
    private static final Map<String, String> FED_ACCOUNT;
    
    // Vostro accounts: Foreign bank accounts maintained at Wells Fargo (for inbound payments)
    private static final Map<String, Map<String, Object>> VOSTRO_ACCOUNTS;
    
    // CHIPS nostro accounts: CHIPS participant accounts maintained at Wells Fargo
    private static final Map<String, Map<String, Object>> CHIPS_NOSTRO_ACCOUNTS;
    
    // SWIFT out nostro accounts: Foreign bank accounts maintained at Wells Fargo (for outbound payments)
    private static final Map<String, Map<String, Object>> SWIFT_OUT_NOSTRO_ACCOUNTS;
    
    static {
        // FED Account
        Map<String, String> fed = new HashMap<>();
        fed.put("account_number", "WF-FED-SETTLE-001");
        fed.put("account_name", "Wells Fargo FED Settlement Account");
        fed.put("routing_number", "121000248");
        FED_ACCOUNT = Collections.unmodifiableMap(fed);
        
        // Vostro Accounts
        Map<String, Map<String, Object>> vostro = new HashMap<>();
        
        // State Bank of India
        Map<String, Object> sbi = new HashMap<>();
        sbi.put("account_number", "WF-VOSTRO-SBI-USD-001");
        sbi.put("account_name", "State Bank of India Vostro Account");
        sbi.put("currency", "USD");
        sbi.put("bank_name", "State Bank of India");
        sbi.put("country", "IN");
        vostro.put("SBININBB", sbi);
        
        // Deutsche Bank
        Map<String, Object> deut = new HashMap<>();
        deut.put("account_number", "WF-VOSTRO-DEUT-USD-001");
        deut.put("account_name", "Deutsche Bank AG Vostro Account");
        deut.put("currency", "USD");
        deut.put("bank_name", "Deutsche Bank AG");
        deut.put("country", "DE");
        vostro.put("DEUTDEFF", deut);
        
        // HSBC
        Map<String, Object> hsbc = new HashMap<>();
        hsbc.put("account_number", "WF-VOSTRO-HSBC-USD-001");
        hsbc.put("account_name", "HSBC Bank plc Vostro Account");
        hsbc.put("currency", "USD");
        hsbc.put("bank_name", "HSBC Bank plc");
        hsbc.put("country", "GB");
        vostro.put("HSBCGB2L", hsbc);
        
        // Banamex (Mexico)
        Map<String, Object> banamex = new HashMap<>();
        banamex.put("account_number", "WF-VOSTRO-BANAMEX-USD-001");
        banamex.put("account_name", "Banco Nacional de Mexico Vostro Account");
        banamex.put("currency", "USD");
        banamex.put("bank_name", "Banco Nacional de Mexico");
        banamex.put("country", "MX");
        vostro.put("BAMXMXMM", banamex);
        
        // Standard Chartered
        Map<String, Object> scbl = new HashMap<>();
        scbl.put("account_number", "WF-VOSTRO-SCBL-USD-001");
        scbl.put("account_name", "Standard Chartered Bank Vostro Account");
        scbl.put("currency", "USD");
        scbl.put("bank_name", "Standard Chartered Bank");
        scbl.put("country", "GB");
        vostro.put("SCBLUS33", scbl);
        
        VOSTRO_ACCOUNTS = Collections.unmodifiableMap(vostro);
        
        // CHIPS Nostro Accounts
        Map<String, Map<String, Object>> chips = new HashMap<>();
        
        // Chase
        Map<String, Object> chase = new HashMap<>();
        chase.put("account_number", "WF-CHIPS-NOSTRO-CHASE-001");
        chase.put("account_name", "JPMorgan Chase Bank NA CHIPS Nostro Account");
        chase.put("currency", "USD");
        chase.put("bank_name", "JPMorgan Chase Bank NA");
        chase.put("chips_uid", "0002");
        chase.put("country", "US");
        chips.put("CHASUS33", chase);
        
        // Bank of America
        Map<String, Object> bofa = new HashMap<>();
        bofa.put("account_number", "WF-CHIPS-NOSTRO-BOFA-001");
        bofa.put("account_name", "Bank of America NA CHIPS Nostro Account");
        bofa.put("currency", "USD");
        bofa.put("bank_name", "Bank of America NA");
        bofa.put("chips_uid", "0003");
        bofa.put("country", "US");
        chips.put("BOFAUS3N", bofa);
        
        // Citibank
        Map<String, Object> citi = new HashMap<>();
        citi.put("account_number", "WF-CHIPS-NOSTRO-CITI-001");
        citi.put("account_name", "Citibank NA CHIPS Nostro Account");
        citi.put("currency", "USD");
        citi.put("bank_name", "Citibank NA");
        citi.put("chips_uid", "0004");
        citi.put("country", "US");
        chips.put("CITIUS33", citi);
        
        // Deutsche Bank
        Map<String, Object> deutChips = new HashMap<>();
        deutChips.put("account_number", "WF-CHIPS-NOSTRO-DEUT-001");
        deutChips.put("account_name", "Deutsche Bank AG CHIPS Nostro Account");
        deutChips.put("currency", "USD");
        deutChips.put("bank_name", "Deutsche Bank AG");
        deutChips.put("chips_uid", "0407");
        deutChips.put("country", "DE");
        chips.put("DEUTDEFF", deutChips);
        
        // HSBC
        Map<String, Object> hsbcChips = new HashMap<>();
        hsbcChips.put("account_number", "WF-CHIPS-NOSTRO-HSBC-001");
        hsbcChips.put("account_name", "HSBC Bank plc CHIPS Nostro Account");
        hsbcChips.put("currency", "USD");
        hsbcChips.put("bank_name", "HSBC Bank plc");
        hsbcChips.put("chips_uid", "0408");
        hsbcChips.put("country", "GB");
        chips.put("HSBCGB2L", hsbcChips);
        
        CHIPS_NOSTRO_ACCOUNTS = Collections.unmodifiableMap(chips);
        
        // SWIFT Out Nostro Accounts
        Map<String, Map<String, Object>> swift = new HashMap<>();
        
        // Banamex
        Map<String, Object> banamexSwift = new HashMap<>();
        banamexSwift.put("account_number", "WF-SWIFT-NOSTRO-BANAMEX-001");
        banamexSwift.put("account_name", "Banco Nacional de Mexico Nostro Account");
        banamexSwift.put("currency", "USD");
        banamexSwift.put("bank_name", "Banco Nacional de Mexico");
        banamexSwift.put("country", "MX");
        swift.put("BAMXMXMM", banamexSwift);
        
        // State Bank of India
        Map<String, Object> sbiSwift = new HashMap<>();
        sbiSwift.put("account_number", "WF-SWIFT-NOSTRO-SBI-001");
        sbiSwift.put("account_name", "State Bank of India Nostro Account");
        sbiSwift.put("currency", "USD");
        sbiSwift.put("bank_name", "State Bank of India");
        sbiSwift.put("country", "IN");
        swift.put("SBININBB", sbiSwift);
        
        // Deutsche Bank
        Map<String, Object> deutSwift = new HashMap<>();
        deutSwift.put("account_number", "WF-SWIFT-NOSTRO-DEUT-001");
        deutSwift.put("account_name", "Deutsche Bank AG Nostro Account");
        deutSwift.put("currency", "USD");
        deutSwift.put("bank_name", "Deutsche Bank AG");
        deutSwift.put("country", "DE");
        swift.put("DEUTDEFF", deutSwift);
        
        // HSBC
        Map<String, Object> hsbcSwift = new HashMap<>();
        hsbcSwift.put("account_number", "WF-SWIFT-NOSTRO-HSBC-001");
        hsbcSwift.put("account_name", "HSBC Bank plc Nostro Account");
        hsbcSwift.put("currency", "USD");
        hsbcSwift.put("bank_name", "HSBC Bank plc");
        hsbcSwift.put("country", "GB");
        swift.put("HSBCGB2L", hsbcSwift);
        
        // Standard Chartered
        Map<String, Object> scblSwift = new HashMap<>();
        scblSwift.put("account_number", "WF-SWIFT-NOSTRO-SCBL-001");
        scblSwift.put("account_name", "Standard Chartered Bank Nostro Account");
        scblSwift.put("currency", "USD");
        scblSwift.put("bank_name", "Standard Chartered Bank");
        scblSwift.put("country", "GB");
        swift.put("SCBLUS33", scblSwift);
        
        SWIFT_OUT_NOSTRO_ACCOUNTS = Collections.unmodifiableMap(swift);
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
        
        Map<String, Object> account = VOSTRO_ACCOUNTS.get(bic8);
        return Optional.ofNullable(account != null ? new HashMap<>(account) : null);
    }
    
    /**
     * Lookup Wells Fargo's FED settlement account.
     * 
     * @return FED account data
     */
    public Map<String, String> lookupFedAccount() {
        return new HashMap<>(FED_ACCOUNT);
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
        
        Map<String, Object> account = CHIPS_NOSTRO_ACCOUNTS.get(bic8);
        return Optional.ofNullable(account != null ? new HashMap<>(account) : null);
    }
    
    /**
     * Lookup SWIFT out nostro account for a foreign bank.
     * 
     * @param bic Foreign bank BIC code (BIC8 or BIC11 format)
     * @return Optional with account data or empty if not found
     */
    public Optional<Map<String, Object>> lookupSwiftOutNostroAccount(String bic) {
        if (bic == null || bic.trim().isEmpty()) {
            return Optional.empty();
        }
        
        // Normalize BIC to BIC8 (first 8 chars) for lookup
        String bic8 = bic.trim().toUpperCase();
        if (bic8.length() > 8) {
            bic8 = bic8.substring(0, 8);
        }
        
        Map<String, Object> account = SWIFT_OUT_NOSTRO_ACCOUNTS.get(bic8);
        return Optional.ofNullable(account != null ? new HashMap<>(account) : null);
    }
}

