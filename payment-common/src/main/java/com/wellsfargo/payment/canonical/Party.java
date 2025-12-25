package com.wellsfargo.payment.canonical;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Network-agnostic representation of a party (debtor/creditor).
 * 
 * Can represent individuals, corporations, or financial institutions.
 * This class is rail-agnostic and does not contain any rail-specific fields.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Party {
    /**
     * Party name.
     */
    private String name;

    /**
     * Account identifier.
     */
    private String accountId;

    /**
     * Account type (e.g., "CHECKING", "SAVINGS").
     */
    private String accountType;

    /**
     * Address information.
     */
    private String address;

    /**
     * ISO 3166-1 alpha-2 country code.
     */
    private String country;
}

