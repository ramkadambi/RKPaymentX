package com.wellsfargo.payment.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Rule actions to apply when a rule matches.
 * 
 * Supports:
 * - routing_type: Type of routing (IBT, INTERMEDIARY, etc.)
 * - selected_network: Network to use (INTERNAL, FED, CHIPS, SWIFT)
 * - next_hop: Next hop information
 * - message_type: Message type to generate
 * - invoke_services: List of services to invoke
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleActions {
    @JsonProperty("routing_type")
    private String routingType;
    
    @JsonProperty("selected_network")
    private String selectedNetwork;
    
    @JsonProperty("next_hop")
    private String nextHop;
    
    @JsonProperty("message_type")
    private String messageType;
    
    @JsonProperty("invoke_services")
    private List<String> invokeServices;
    
    // Allow additional properties for extensibility
    private Map<String, Object> additionalProperties;
}

