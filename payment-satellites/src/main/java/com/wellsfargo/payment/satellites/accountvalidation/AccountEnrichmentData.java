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
    
    // Correspondent bank fields for non-FED-enabled banks
    private boolean requiresCorrespondent;
    private String correspondentBankBic;
    private String correspondentBankName;
    private boolean correspondentFedEnabled;
    private boolean correspondentChipsEnabled;
    private String bankCategory; // CHIPS_PARTICIPANT, FED_ENABLED, NON_FED_ENABLED
}

