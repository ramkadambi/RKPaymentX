package com.wellsfargo.payment.rules;

import com.wellsfargo.payment.canonical.RoutingTrace;
import com.wellsfargo.payment.canonical.enums.RoutingNetwork;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of rule evaluation.
 * 
 * Contains the selected network, applied rule information, and routing trace.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleEvaluationResult {
    /**
     * Selected routing network.
     */
    private RoutingNetwork selectedNetwork;
    
    /**
     * Rule ID that was applied.
     */
    private String appliedRuleId;
    
    /**
     * Priority of the applied rule.
     */
    private Integer appliedRulePriority;
    
    /**
     * Description of the applied rule.
     */
    private String appliedRuleDescription;
    
    /**
     * Routing trace entries explaining the evaluation process.
     */
    @Builder.Default
    private List<RoutingTrace> routingTrace = new ArrayList<>();
    
    /**
     * Whether a rule was successfully applied.
     */
    private boolean ruleApplied;
    
    /**
     * Additional routing decision metadata.
     */
    private java.util.Map<String, Object> routingDecision;
}

