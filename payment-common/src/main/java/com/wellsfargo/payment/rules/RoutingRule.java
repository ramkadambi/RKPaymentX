package com.wellsfargo.payment.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single routing rule.
 * 
 * Rules are evaluated in priority order, and the first matching rule wins.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutingRule {
    @JsonProperty("rule_id")
    private String ruleId;
    
    private Integer priority;
    
    private String description;
    
    private RuleConditions conditions;
    
    @JsonProperty("decision_matrix")
    private DecisionMatrix decisionMatrix;
    
    private RuleActions actions;
}

