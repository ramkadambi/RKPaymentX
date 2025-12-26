package com.wellsfargo.payment.satellites.paymentposting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wellsfargo.payment.canonical.PaymentEvent;
import com.wellsfargo.payment.canonical.enums.RoutingNetwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.*;

/**
 * Reconciliation Validator for payment transactions.
 * 
 * Validates that debits and credits are correctly applied to:
 * - Vostro accounts (for inbound payments)
 * - Nostro accounts (CHIPS/SWIFT for outbound/external payments)
 * - FED settlement account
 * - Customer accounts
 */
@Component
public class ReconciliationValidator {
    
    private static final Logger log = LoggerFactory.getLogger(ReconciliationValidator.class);
    private static final String LEDGER_FILE = "data/test_ledger.json";
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private Map<String, AccountBalance> accountBalances = new HashMap<>();
    
    /**
     * Load account balances from ledger.
     */
    public void loadLedger() {
        try {
            ClassPathResource resource = new ClassPathResource(LEDGER_FILE);
            if (!resource.exists()) {
                log.warn("Ledger file not found: {}", LEDGER_FILE);
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
                        JsonNode openingBalance = account.get("opening_balance");
                        JsonNode currency = account.get("currency");
                        JsonNode accountType = account.get("account_type");
                        
                        if (balance != null) {
                            BigDecimal balanceValue;
                            if (balance.isTextual()) {
                                balanceValue = new BigDecimal(balance.asText());
                            } else if (balance.isNumber()) {
                                balanceValue = balance.decimalValue();
                            } else {
                                return;
                            }
                            
                            BigDecimal opening = openingBalance != null && openingBalance.isTextual() 
                                ? new BigDecimal(openingBalance.asText()) : balanceValue;
                            
                            accountBalances.put(accountId, new AccountBalance(
                                accountId,
                                opening,
                                balanceValue,
                                currency != null ? currency.asText() : "USD",
                                accountType != null ? accountType.asText() : "UNKNOWN"
                            ));
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
     * Validate reconciliation for a payment transaction.
     * 
     * @param paymentEvent The payment event
     * @param debitAccount Debit account ID
     * @param debitAccountType Debit account type
     * @param creditAccount Credit account ID
     * @param creditAccountType Credit account type
     * @param amount Payment amount
     * @return ReconciliationResult with validation details
     */
    public ReconciliationResult validateReconciliation(
            PaymentEvent paymentEvent,
            String debitAccount,
            String debitAccountType,
            String creditAccount,
            String creditAccountType,
            BigDecimal amount) {
        
        ReconciliationResult result = new ReconciliationResult();
        result.setPaymentEvent(paymentEvent);
        result.setDebitAccount(debitAccount);
        result.setDebitAccountType(debitAccountType);
        result.setCreditAccount(creditAccount);
        result.setCreditAccountType(creditAccountType);
        result.setAmount(amount);
        
        // Get initial balances
        AccountBalance debitBalanceBefore = accountBalances.get(debitAccount);
        AccountBalance creditBalanceBefore = accountBalances.get(creditAccount);
        
        if (debitBalanceBefore == null) {
            result.addError("Debit account not found in ledger: " + debitAccount);
            return result;
        }
        
        if (creditBalanceBefore == null) {
            result.addError("Credit account not found in ledger: " + creditAccount);
            return result;
        }
        
        result.setDebitBalanceBefore(debitBalanceBefore.getCurrentBalance());
        result.setCreditBalanceBefore(creditBalanceBefore.getCurrentBalance());
        
        // Calculate expected balances after transaction
        BigDecimal debitBalanceAfter = debitBalanceBefore.getCurrentBalance().subtract(amount);
        BigDecimal creditBalanceAfter = creditBalanceBefore.getCurrentBalance().add(amount);
        
        result.setDebitBalanceAfter(debitBalanceAfter);
        result.setCreditBalanceAfter(creditBalanceAfter);
        
        // Validate debit account has sufficient balance
        if (debitBalanceAfter.compareTo(BigDecimal.ZERO) < 0) {
            result.addError(String.format(
                "Insufficient balance in debit account %s: Current=%.2f, Required=%.2f, After=%.2f",
                debitAccount, debitBalanceBefore.getCurrentBalance(), amount, debitBalanceAfter));
        }
        
        // Validate account types match expected settlement logic
        RoutingNetwork selectedNetwork = paymentEvent.getRoutingContext() != null 
            ? paymentEvent.getRoutingContext().getSelectedNetwork() : null;
        
        String debtorBic = paymentEvent.getDebtorAgent() != null 
            ? paymentEvent.getDebtorAgent().getIdValue() : null;
        String creditorBic = paymentEvent.getCreditorAgent() != null 
            ? paymentEvent.getCreditorAgent().getIdValue() : null;
        
        boolean isInbound = debtorBic != null && creditorBic != null 
            && !normalizeBic(debtorBic).equals("WFBIUS6S") 
            && normalizeBic(creditorBic).equals("WFBIUS6S");
        boolean isOutbound = debtorBic != null && creditorBic != null 
            && normalizeBic(debtorBic).equals("WFBIUS6S") 
            && !normalizeBic(creditorBic).equals("WFBIUS6S");
        
        // Validate account types based on payment direction and network
        if (isInbound) {
            // Inbound: Debit should be VOSTRO
            if (!"VOSTRO".equals(debitAccountType)) {
                result.addWarning(String.format(
                    "Inbound payment: Expected debit account type VOSTRO, got %s", debitAccountType));
            }
            
            // Credit depends on beneficiary and network
            if (selectedNetwork == RoutingNetwork.INTERNAL || 
                normalizeBic(creditorBic).equals("WFBIUS6S")) {
                // Wells customer - should credit to CUSTOMER
                if (!"CUSTOMER".equals(creditAccountType)) {
                    result.addWarning(String.format(
                        "Inbound payment to Wells customer: Expected credit account type CUSTOMER, got %s", 
                        creditAccountType));
                }
            } else if (selectedNetwork == RoutingNetwork.FED) {
                // External via FED - should credit to FED
                if (!"FED".equals(creditAccountType)) {
                    result.addWarning(String.format(
                        "Inbound FED payment: Expected credit account type FED, got %s", creditAccountType));
                }
            } else if (selectedNetwork == RoutingNetwork.CHIPS) {
                // External via CHIPS - should credit to CHIPS_NOSTRO
                if (!"CHIPS_NOSTRO".equals(creditAccountType)) {
                    result.addWarning(String.format(
                        "Inbound CHIPS payment: Expected credit account type CHIPS_NOSTRO, got %s", 
                        creditAccountType));
                }
            } else if (selectedNetwork == RoutingNetwork.SWIFT) {
                // External via SWIFT - should credit to SWIFT_NOSTRO
                if (!"SWIFT_NOSTRO".equals(creditAccountType)) {
                    result.addWarning(String.format(
                        "Inbound SWIFT payment: Expected credit account type SWIFT_NOSTRO, got %s", 
                        creditAccountType));
                }
            }
        } else if (isOutbound) {
            // Outbound: Debit should be CUSTOMER (Wells customer)
            if (!"CUSTOMER".equals(debitAccountType)) {
                result.addWarning(String.format(
                    "Outbound payment: Expected debit account type CUSTOMER, got %s", debitAccountType));
            }
            
            // Credit depends on network
            if (selectedNetwork == RoutingNetwork.FED) {
                // FED outbound - should credit to FED
                if (!"FED".equals(creditAccountType)) {
                    result.addWarning(String.format(
                        "Outbound FED payment: Expected credit account type FED, got %s", creditAccountType));
                }
            } else if (selectedNetwork == RoutingNetwork.CHIPS) {
                // CHIPS outbound - should credit to CHIPS_NOSTRO
                if (!"CHIPS_NOSTRO".equals(creditAccountType)) {
                    result.addWarning(String.format(
                        "Outbound CHIPS payment: Expected credit account type CHIPS_NOSTRO, got %s", 
                        creditAccountType));
                }
            } else if (selectedNetwork == RoutingNetwork.SWIFT) {
                // SWIFT outbound - should credit to SWIFT_NOSTRO
                if (!"SWIFT_NOSTRO".equals(creditAccountType)) {
                    result.addWarning(String.format(
                        "Outbound SWIFT payment: Expected credit account type SWIFT_NOSTRO, got %s", 
                        creditAccountType));
                }
            }
        }
        
        // Update balances in memory (for tracking)
        accountBalances.put(debitAccount, new AccountBalance(
            debitAccount,
            debitBalanceBefore.getOpeningBalance(),
            debitBalanceAfter,
            debitBalanceBefore.getCurrency(),
            debitBalanceBefore.getAccountType()
        ));
        
        accountBalances.put(creditAccount, new AccountBalance(
            creditAccount,
            creditBalanceBefore.getOpeningBalance(),
            creditBalanceAfter,
            creditBalanceBefore.getCurrency(),
            creditBalanceBefore.getAccountType()
        ));
        
        result.setValid(result.getErrors().isEmpty());
        
        return result;
    }
    
    /**
     * Get current balance for an account.
     */
    public Optional<BigDecimal> getAccountBalance(String accountId) {
        AccountBalance balance = accountBalances.get(accountId);
        return balance != null ? Optional.of(balance.getCurrentBalance()) : Optional.empty();
    }
    
    /**
     * Get all account balances.
     */
    public Map<String, AccountBalance> getAllAccountBalances() {
        return new HashMap<>(accountBalances);
    }
    
    private String normalizeBic(String bic) {
        if (bic == null) return "";
        String normalized = bic.trim().toUpperCase();
        return normalized.length() > 8 ? normalized.substring(0, 8) : normalized;
    }
    
    /**
     * Account balance holder.
     */
    public static class AccountBalance {
        private final String accountId;
        private final BigDecimal openingBalance;
        private final BigDecimal currentBalance;
        private final String currency;
        private final String accountType;
        
        public AccountBalance(String accountId, BigDecimal openingBalance, BigDecimal currentBalance, 
                             String currency, String accountType) {
            this.accountId = accountId;
            this.openingBalance = openingBalance;
            this.currentBalance = currentBalance;
            this.currency = currency;
            this.accountType = accountType;
        }
        
        public String getAccountId() { return accountId; }
        public BigDecimal getOpeningBalance() { return openingBalance; }
        public BigDecimal getCurrentBalance() { return currentBalance; }
        public String getCurrency() { return currency; }
        public String getAccountType() { return accountType; }
    }
    
    /**
     * Reconciliation result.
     */
    public static class ReconciliationResult {
        private PaymentEvent paymentEvent;
        private String debitAccount;
        private String debitAccountType;
        private String creditAccount;
        private String creditAccountType;
        private BigDecimal amount;
        private BigDecimal debitBalanceBefore;
        private BigDecimal creditBalanceBefore;
        private BigDecimal debitBalanceAfter;
        private BigDecimal creditBalanceAfter;
        private List<String> errors = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();
        private boolean valid;
        
        public void addError(String error) {
            errors.add(error);
        }
        
        public void addWarning(String warning) {
            warnings.add(warning);
        }
        
        // Getters and setters
        public PaymentEvent getPaymentEvent() { return paymentEvent; }
        public void setPaymentEvent(PaymentEvent paymentEvent) { this.paymentEvent = paymentEvent; }
        public String getDebitAccount() { return debitAccount; }
        public void setDebitAccount(String debitAccount) { this.debitAccount = debitAccount; }
        public String getDebitAccountType() { return debitAccountType; }
        public void setDebitAccountType(String debitAccountType) { this.debitAccountType = debitAccountType; }
        public String getCreditAccount() { return creditAccount; }
        public void setCreditAccount(String creditAccount) { this.creditAccount = creditAccount; }
        public String getCreditAccountType() { return creditAccountType; }
        public void setCreditAccountType(String creditAccountType) { this.creditAccountType = creditAccountType; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
        public BigDecimal getDebitBalanceBefore() { return debitBalanceBefore; }
        public void setDebitBalanceBefore(BigDecimal debitBalanceBefore) { this.debitBalanceBefore = debitBalanceBefore; }
        public BigDecimal getCreditBalanceBefore() { return creditBalanceBefore; }
        public void setCreditBalanceBefore(BigDecimal creditBalanceBefore) { this.creditBalanceBefore = creditBalanceBefore; }
        public BigDecimal getDebitBalanceAfter() { return debitBalanceAfter; }
        public void setDebitBalanceAfter(BigDecimal debitBalanceAfter) { this.debitBalanceAfter = debitBalanceAfter; }
        public BigDecimal getCreditBalanceAfter() { return creditBalanceAfter; }
        public void setCreditBalanceAfter(BigDecimal creditBalanceAfter) { this.creditBalanceAfter = creditBalanceAfter; }
        public List<String> getErrors() { return errors; }
        public List<String> getWarnings() { return warnings; }
        public boolean isValid() { return valid; }
        public void setValid(boolean valid) { this.valid = valid; }
    }
}

