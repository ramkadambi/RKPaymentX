package com.wellsfargo.payment.canonical;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Contextual enrichment data added by various processing satellites.
 * 
 * This section contains all enrichment data that is added during processing:
 * - Account validation enrichment
 * - BIC/ABA/CHIPS lookup data
 * - Sanctions check results
 * - Balance check results
 * - Settlement account information
 * 
 * All fields are optional and additive - satellites progressively enrich
 * the payment event without overwriting existing enrichment data.
 * 
 * This class is rail-agnostic - no rail-specific fields are included.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EnrichmentContext {
    /**
     * Account validation enrichment.
     * Structure: {
     *   "status": ServiceResultStatus,
     *   "creditor_type": CreditorType,
     *   "fed_member": boolean,
     *   "chips_member": boolean,
     *   "nostro_accounts_available": boolean,
     *   "vostro_with_us": boolean,
     *   "preferred_correspondent": String (optional)
     * }
     */
    private Map<String, Object> accountValidation;

    /**
     * BIC lookup data.
     * Structure: {
     *   "bank_name": String,
     *   "country": String,
     *   "fed_member": boolean,
     *   "chips_member": boolean,
     *   "aba_routing": String (optional),
     *   "chips_uid": String (optional)
     * }
     */
    private Map<String, Object> bicLookup;

    /**
     * ABA lookup data.
     */
    private Map<String, Object> abaLookup;

    /**
     * CHIPS lookup data.
     */
    private Map<String, Object> chipsLookup;

    /**
     * Sanctions check results.
     * Structure: {
     *   "status": ServiceResultStatus,
     *   "check_timestamp": String (ISO 8601),
     *   "match_found": boolean,
     *   "match_details": Map (optional)
     * }
     */
    private Map<String, Object> sanctionsCheck;

    /**
     * Balance check results.
     * Structure: {
     *   "status": ServiceResultStatus,
     *   "account_id": String,
     *   "available_balance": BigDecimal,
     *   "required_amount": BigDecimal,
     *   "sufficient_funds": boolean
     * }
     */
    private Map<String, Object> balanceCheck;

    /**
     * Settlement account information.
     * Structure: {
     *   "debit_account": String,
     *   "credit_account": String,
     *   "debit_account_type": String (e.g., "VOSTRO", "NOSTRO", "FED", "CHIPS_NOSTRO"),
     *   "credit_account_type": String
     * }
     */
    private Map<String, Object> settlementAccounts;
}

