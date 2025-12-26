package com.wellsfargo.payment.satellites.accountvalidation;

import com.wellsfargo.payment.canonical.enums.CreditorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Mock account lookup service for account validation enrichment.
 * 
 * In production, this would query MongoDB or another database.
 * 
 * Provides enrichment data for:
 * - creditor_type
 * - fed_member
 * - chips_member
 * - preferred_correspondent
 * - nostro_accounts_available
 * - vostro_with_us (whether bank has vostro account with Wells Fargo)
 * - correspondent bank information (for non-FED-enabled banks)
 */
@Slf4j
@Component
public class AccountLookupService {
    
    @Autowired(required = false)
    private BankCapabilitiesLookupService bankCapabilitiesLookupService;
    
    // Mock reference data: creditor BIC8 -> enrichment data
    // In production, this would be a MongoDB collection or Redis cache
    private static final Map<String, AccountEnrichmentData> ACCOUNT_LOOKUP = new HashMap<>();
    
    static {
        // Wells Fargo
        ACCOUNT_LOOKUP.put("WFBIUS6S", AccountEnrichmentData.builder()
            .creditorType(CreditorType.BANK)
            .fedMember(true)
            .chipsMember(true)
            .nostroAccountsAvailable(true)
            .vostroWithUs(true)
            .preferredCorrespondent("WFBIUS6SXXX")
            .build());
        
        // US Banks - FED members
        ACCOUNT_LOOKUP.put("CHASUS33", AccountEnrichmentData.builder()
            .creditorType(CreditorType.BANK)
            .fedMember(true)
            .chipsMember(true)
            .nostroAccountsAvailable(true)
            .vostroWithUs(true)
            .preferredCorrespondent("CHASUS33XXX")
            .build());
        
        ACCOUNT_LOOKUP.put("BOFAUS3N", AccountEnrichmentData.builder()
            .creditorType(CreditorType.BANK)
            .fedMember(true)
            .chipsMember(true)
            .nostroAccountsAvailable(true)
            .vostroWithUs(true)
            .preferredCorrespondent("BOFAUS3NXXX")
            .build());
        
        ACCOUNT_LOOKUP.put("USBKUS44", AccountEnrichmentData.builder()
            .creditorType(CreditorType.BANK)
            .fedMember(true)
            .chipsMember(false)
            .nostroAccountsAvailable(true)
            .vostroWithUs(true)
            .preferredCorrespondent("USBKUS44XXX")
            .build());
        
        // Foreign banks - CHIPS members
        ACCOUNT_LOOKUP.put("DEUTDEFF", AccountEnrichmentData.builder()
            .creditorType(CreditorType.BANK)
            .fedMember(false)
            .chipsMember(true)
            .nostroAccountsAvailable(false)
            .vostroWithUs(false)
            .preferredCorrespondent("DEUTDEFFXXX")
            .build());
        
        ACCOUNT_LOOKUP.put("HSBCGB2L", AccountEnrichmentData.builder()
            .creditorType(CreditorType.BANK)
            .fedMember(false)
            .chipsMember(true)
            .nostroAccountsAvailable(false)
            .vostroWithUs(false)
            .preferredCorrespondent("HSBCGB2LXXX")
            .build());
        
        // Foreign banks - no CHIPS
        ACCOUNT_LOOKUP.put("SBININBB", AccountEnrichmentData.builder()
            .creditorType(CreditorType.BANK)
            .fedMember(false)
            .chipsMember(false)
            .nostroAccountsAvailable(false)
            .vostroWithUs(false)
            .preferredCorrespondent("SBININBBXXX")
            .build());
        
        ACCOUNT_LOOKUP.put("BAMXMXMM", AccountEnrichmentData.builder()
            .creditorType(CreditorType.BANK)
            .fedMember(false)
            .chipsMember(false)
            .nostroAccountsAvailable(false)
            .vostroWithUs(false)
            .preferredCorrespondent("BAMXMXMMXXX")
            .build());
    }
    
    /**
     * Lookup account enrichment data by creditor BIC.
     * 
     * First checks bank_capabilities.json for US banks, then falls back to static lookup.
     * 
     * @param creditorBic Creditor bank BIC (BIC8 or BIC11 format)
     * @return Optional AccountEnrichmentData or empty if not found
     */
    public Optional<AccountEnrichmentData> lookupAccountEnrichment(String creditorBic) {
        if (creditorBic == null || creditorBic.trim().isEmpty()) {
            return Optional.empty();
        }
        
        // Normalize BIC to BIC8 (first 8 chars) for lookup
        String bic8 = creditorBic.trim().toUpperCase();
        if (bic8.length() >= 8) {
            bic8 = bic8.substring(0, 8);
        }
        
        // First, try bank capabilities lookup (for US banks with correspondent relationships)
        if (bankCapabilitiesLookupService != null) {
            Optional<BankCapabilitiesLookupService.BankCapability> capability = 
                bankCapabilitiesLookupService.lookupBankCapability(bic8);
            
            if (capability.isPresent()) {
                return Optional.of(convertCapabilityToEnrichmentData(capability.get()));
            }
        }
        
        // Fall back to static lookup (for foreign banks and legacy data)
        AccountEnrichmentData data = ACCOUNT_LOOKUP.get(bic8);
        if (data == null) {
            log.debug("Account not found in lookup for BIC: {}", creditorBic);
            return Optional.empty();
        }
        
        return Optional.of(data);
    }
    
    /**
     * Convert BankCapability to AccountEnrichmentData.
     */
    private AccountEnrichmentData convertCapabilityToEnrichmentData(
            BankCapabilitiesLookupService.BankCapability capability) {
        
        AccountEnrichmentData.AccountEnrichmentDataBuilder builder = AccountEnrichmentData.builder()
            .creditorType(CreditorType.BANK)
            .fedMember(capability.isFedEnabled())
            .chipsMember(capability.isChipsEnabled())
            .bankCategory(capability.getBankCategory());
        
        // Determine nostro/vostro availability based on bank type
        // CHIPS participants and FED-enabled banks typically have nostro accounts
        boolean hasNostro = capability.isFedEnabled() || capability.isChipsEnabled();
        builder.nostroAccountsAvailable(hasNostro);
        
        // Vostro accounts are typically for foreign banks, but some US banks may have them
        // For now, assume CHIPS participants have vostro accounts with Wells
        builder.vostroWithUs(capability.isChipsEnabled());
        
        // Set preferred correspondent (self for direct access, or correspondent for non-FED)
        if (capability.isFedEnabled()) {
            builder.preferredCorrespondent(capability.getBic() + "XXX");
        } else if (capability.getCorrespondentBank() != null) {
            BankCapabilitiesLookupService.CorrespondentBank correspondent = capability.getCorrespondentBank();
            builder.preferredCorrespondent(correspondent.getBic() + "XXX");
        }
        
        // Set correspondent bank information if required
        if (capability.getCorrespondentBank() != null) {
            BankCapabilitiesLookupService.CorrespondentBank correspondent = capability.getCorrespondentBank();
            builder.requiresCorrespondent(true)
                .correspondentBankBic(correspondent.getBic())
                .correspondentBankName(correspondent.getBankName())
                .correspondentFedEnabled(correspondent.isFedEnabled())
                .correspondentChipsEnabled(correspondent.isChipsEnabled());
        } else {
            builder.requiresCorrespondent(false);
        }
        
        return builder.build();
    }
}

