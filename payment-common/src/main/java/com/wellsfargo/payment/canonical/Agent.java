package com.wellsfargo.payment.canonical;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Network-agnostic representation of a financial institution/agent.
 * 
 * Supports multiple identification schemes:
 * - BIC (Bank Identifier Code)
 * - ABA (American Bankers Association routing number)
 * - CHIPS UID (CHIPS Universal Identifier)
 * - LEI (Legal Entity Identifier)
 * - National clearing codes
 * 
 * This class is rail-agnostic and does not contain any rail-specific fields.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Agent {
    /**
     * Identification scheme (e.g., "BIC", "ABA", "CHIPS_UID", "LEI").
     * Must be set together with idValue, or both must be null.
     */
    private String idScheme;

    /**
     * The actual identifier value.
     * Must be set together with idScheme, or both must be null.
     */
    private String idValue;

    /**
     * Bank/agent name.
     */
    private String name;

    /**
     * ISO 3166-1 alpha-2 country code.
     */
    private String country;

    /**
     * Optional address information.
     */
    private String address;
}

