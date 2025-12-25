package com.wellsfargo.payment.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Root configuration object for routing rules.
 * 
 * Represents the structure of routing_rulesV2.json.
 * This is a data-only class - no business logic.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutingRulesConfig {
    @JsonProperty("bank_context")
    private BankContext bankContext;
    
    @JsonProperty("external_services")
    private Map<String, String> externalServices;
    
    private List<RoutingRule> rules;
}

