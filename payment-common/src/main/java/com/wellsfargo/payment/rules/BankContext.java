package com.wellsfargo.payment.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bank context information from routing rules configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BankContext {
    @JsonProperty("bank_name")
    private String bankName;
    
    @JsonProperty("bank_bic")
    private String bankBic;
    
    private String country;
}

