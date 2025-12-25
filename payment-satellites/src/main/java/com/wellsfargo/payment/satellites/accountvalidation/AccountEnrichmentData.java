package com.wellsfargo.payment.satellites.accountvalidation;

import com.wellsfargo.payment.canonical.enums.CreditorType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Account enrichment data for account validation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountEnrichmentData {
    private CreditorType creditorType;
    private boolean fedMember;
    private boolean chipsMember;
    private boolean nostroAccountsAvailable;
    private boolean vostroWithUs;
    private String preferredCorrespondent;
}

