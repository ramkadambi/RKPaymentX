package com.wellsfargo.payment.rules;

import com.wellsfargo.payment.canonical.*;
import com.wellsfargo.payment.canonical.enums.RoutingNetwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * Routing rules engine for evaluating routing rules against PaymentEvent.
 * 
 * This engine:
 * - Evaluates rules in priority order
 * - First matching rule wins
 * - Produces RoutingTrace entries explaining decisions
 * - Is deterministic and thread-safe
 * - Contains no business logic - pure rule evaluation
 * 
 * Usage:
 * <pre>
 * RoutingRulesLoader loader = new RoutingRulesLoader();
 * RoutingRulesConfig config = loader.loadRules();
 * RoutingRulesEngine engine = new RoutingRulesEngine(config);
 * RuleEvaluationResult result = engine.evaluate(event);
 * </pre>
 */
public class RoutingRulesEngine {
    
    private static final Logger log = LoggerFactory.getLogger(RoutingRulesEngine.class);
    
    private final RoutingRulesConfig config;
    
    public RoutingRulesEngine(RoutingRulesConfig config) {
        this.config = config;
    }
    
    /**
     * Evaluate routing rules against a PaymentEvent.
     * 
     * @param event PaymentEvent to evaluate
     * @return RuleEvaluationResult with selected network and routing trace
     */
    public RuleEvaluationResult evaluate(PaymentEvent event) {
        List<RoutingTrace> trace = new ArrayList<>();
        Map<String, Object> routingContext = buildRoutingContext(event);
        
        // Evaluate rules in priority order
        for (RoutingRule rule : config.getRules()) {
            boolean matched = evaluateConditions(rule.getConditions(), event, routingContext);
            
            if (matched) {
                addTraceEntry(trace, rule.getRuleId(), "MATCHED", 
                    "Rule conditions matched: " + rule.getDescription());
                
                // Check if rule only invokes services
                if (rule.getActions() != null && 
                    rule.getActions().getInvokeServices() != null &&
                    !rule.getActions().getInvokeServices().isEmpty() &&
                    rule.getActions().getSelectedNetwork() == null) {
                    
                    addTraceEntry(trace, rule.getRuleId(), "SERVICES_INVOKED",
                        "Rule only invokes services, continuing to next rule");
                    
                    // Update context for service invocation rules (e.g., R2)
                    updateContextForServiceRule(rule, event, routingContext, trace);
                    continue;
                }
                
                // Apply rule actions
                return applyRuleActions(rule, event, routingContext, trace);
                
            } else {
                addTraceEntry(trace, rule.getRuleId(), "SKIPPED",
                    "Rule conditions did not match");
            }
        }
        
        // No rule matched - fallback
        addTraceEntry(trace, "NO_RULE_MATCHED", "NO_RULE_MATCHED",
            "No routing rule matched, using SWIFT fallback");
        
        return RuleEvaluationResult.builder()
            .selectedNetwork(RoutingNetwork.SWIFT)
            .appliedRuleId(null)
            .appliedRulePriority(null)
            .appliedRuleDescription("Fallback: No rule matched")
            .routingTrace(trace)
            .ruleApplied(false)
            .build();
    }
    
    /**
     * Build routing context from PaymentEvent for condition evaluation.
     */
    private Map<String, Object> buildRoutingContext(PaymentEvent event) {
        Map<String, Object> ctx = new HashMap<>();
        
        // pacs.008 context (customer-initiated payment)
        Map<String, Object> pacs008Ctx = new HashMap<>();
        if (event.getCreditorAgent() != null && event.getCreditorAgent().getIdValue() != null) {
            pacs008Ctx.put("creditor_agent_bic", event.getCreditorAgent().getIdValue());
        }
        if (event.getDebtorAgent() != null && event.getDebtorAgent().getIdValue() != null) {
            pacs008Ctx.put("debtor_agent_bic", event.getDebtorAgent().getIdValue());
        }
        ctx.put("pacs.008", pacs008Ctx);
        
        // pacs.009 context (financial institution-initiated payment)
        Map<String, Object> pacs009Ctx = new HashMap<>();
        if (event.getCreditorAgent() != null && event.getCreditorAgent().getIdValue() != null) {
            pacs009Ctx.put("creditor_agent_bic", event.getCreditorAgent().getIdValue());
        }
        if (event.getDebtorAgent() != null && event.getDebtorAgent().getIdValue() != null) {
            pacs009Ctx.put("debtor_agent_bic", event.getDebtorAgent().getIdValue());
        }
        if (event.getRoutingContext() != null && event.getRoutingContext().getAgentChain() != null &&
            !event.getRoutingContext().getAgentChain().isEmpty()) {
            Agent intermediary = event.getRoutingContext().getAgentChain().get(0);
            if (intermediary.getIdValue() != null) {
                pacs009Ctx.put("intermediary_bic", intermediary.getIdValue());
            }
        }
        ctx.put("pacs.009", pacs009Ctx);
        
        // Ultimate creditor country
        if (event.getCreditor() != null && event.getCreditor().getCountry() != null) {
            ctx.put("ultimate_creditor_country", event.getCreditor().getCountry());
        } else if (event.getCreditorAgent() != null && event.getCreditorAgent().getCountry() != null) {
            ctx.put("ultimate_creditor_country", event.getCreditorAgent().getCountry());
        }
        
        // Next bank information (from routing context or enrichment context)
        if (event.getRoutingContext() != null && event.getRoutingContext().getNextBank() != null) {
            ctx.put("next_bank", event.getRoutingContext().getNextBank());
        } else if (event.getEnrichmentContext() != null) {
            // Populate next_bank from account validation (preferred) or bicLookup (fallback)
            Map<String, Object> accountValidation = event.getEnrichmentContext().getAccountValidation();
            Map<String, Object> bicLookup = event.getEnrichmentContext().getBicLookup();
            
            if (event.getCreditorAgent() != null) {
                String creditorBic = event.getCreditorAgent().getIdValue();
                Map<String, Object> nextBank = new HashMap<>();
                nextBank.put("bic", creditorBic);
                
                // Prefer account validation data (fed_enabled, chips_enabled) over bicLookup
                if (accountValidation != null) {
                    nextBank.put("fed_enabled", accountValidation.getOrDefault("fed_member", false));
                    nextBank.put("chips_enabled", accountValidation.getOrDefault("chips_member", false));
                    nextBank.put("requires_correspondent", accountValidation.getOrDefault("requires_correspondent", false));
                }
                
                // Fallback to bicLookup for chips_id_exists and aba_exists (legacy support)
                if (bicLookup != null) {
                    nextBank.put("chips_id_exists", bicLookup.containsKey("chips_uid") && bicLookup.get("chips_uid") != null);
                    nextBank.put("aba_exists", bicLookup.containsKey("aba_routing") && bicLookup.get("aba_routing") != null);
                    nextBank.put("country", bicLookup.get("country"));
                } else if (accountValidation != null) {
                    // Use account validation country if available
                    // Note: country might not be in account validation, so this is optional
                }
                
                ctx.put("next_bank", nextBank);
            }
        }
        
        // Payment ecosystem (from routing context)
        if (event.getRoutingContext() != null && event.getRoutingContext().getPaymentEcosystem() != null) {
            ctx.put("payment_ecosystem", event.getRoutingContext().getPaymentEcosystem());
        }
        
        // Customer preference (from routing context)
        if (event.getRoutingContext() != null && event.getRoutingContext().getCustomerPreference() != null) {
            ctx.put("customer_preference", event.getRoutingContext().getCustomerPreference().getValue());
        }
        
        // Payment urgency (from routing context)
        if (event.getRoutingContext() != null && event.getRoutingContext().getPaymentUrgency() != null) {
            ctx.put("payment_urgency", event.getRoutingContext().getPaymentUrgency().getValue());
        }
        
        // Account validation (from enrichment context)
        if (event.getEnrichmentContext() != null && 
            event.getEnrichmentContext().getAccountValidation() != null) {
            ctx.put("account_validation", event.getEnrichmentContext().getAccountValidation());
        }
        
        return ctx;
    }
    
    /**
     * Evaluate rule conditions against PaymentEvent and routing context.
     */
    private boolean evaluateConditions(RuleConditions conditions, PaymentEvent event, Map<String, Object> ctx) {
        if (conditions == null || conditions.getConditions().isEmpty()) {
            return true; // No conditions means always match
        }
        
        Map<String, Object> conds = conditions.getConditions();
        
        // pacs.008 debtor agent BIC (customer-initiated)
        if (conds.containsKey("pacs.008.debtor_agent_bic")) {
            String expectedBic = String.valueOf(conds.get("pacs.008.debtor_agent_bic"));
            String actualBic = event.getDebtorAgent() != null ? event.getDebtorAgent().getIdValue() : "";
            if (!expectedBic.equalsIgnoreCase(actualBic)) {
                return false;
            }
        }
        
        // pacs.008 creditor agent BIC (customer-initiated)
        if (conds.containsKey("pacs.008.creditor_agent_bic")) {
            String expectedBic = String.valueOf(conds.get("pacs.008.creditor_agent_bic"));
            String actualBic = event.getCreditorAgent() != null ? event.getCreditorAgent().getIdValue() : "";
            if (!expectedBic.equalsIgnoreCase(actualBic)) {
                return false;
            }
        }
        
        // pacs.009 creditor agent BIC (financial institution-initiated)
        if (conds.containsKey("pacs.009.creditor_agent_bic")) {
            String expectedBic = String.valueOf(conds.get("pacs.009.creditor_agent_bic"));
            String actualBic = event.getCreditorAgent() != null ? event.getCreditorAgent().getIdValue() : "";
            if (!expectedBic.equalsIgnoreCase(actualBic)) {
                return false;
            }
        }
        
        // pacs.009 debtor agent BIC (for intermediary lookup - bank-initiated)
        if (conds.containsKey("pacs.009.debtor_agent_bic")) {
            String expectedBic = String.valueOf(conds.get("pacs.009.debtor_agent_bic"));
            String actualBic = event.getDebtorAgent() != null ? event.getDebtorAgent().getIdValue() : "";
            if (!expectedBic.equalsIgnoreCase(actualBic)) {
                return false;
            }
        }
        
        // Ultimate creditor country
        if (conds.containsKey("ultimate_creditor_country")) {
            String expectedCountry = String.valueOf(conds.get("ultimate_creditor_country"));
            String actualCountry = String.valueOf(ctx.getOrDefault("ultimate_creditor_country", ""));
            if (!expectedCountry.equals(actualCountry)) {
                return false;
            }
        }
        
        // Ultimate creditor country NOT
        if (conds.containsKey("ultimate_creditor_country_not")) {
            String notCountry = String.valueOf(conds.get("ultimate_creditor_country_not"));
            String actualCountry = String.valueOf(ctx.getOrDefault("ultimate_creditor_country", ""));
            if (notCountry.equals(actualCountry)) {
                return false;
            }
        }
        
        // Check source message type for pacs.008 rules (customer-initiated)
        // This is implicit - if condition uses pacs.008.*, we should verify source is pacs.008
        boolean hasPacs008Condition = conds.keySet().stream().anyMatch(k -> k.startsWith("pacs.008."));
        if (hasPacs008Condition) {
            if (event.getSourceMessageType() == null || 
                !event.getSourceMessageType().getValue().equals("ISO20022_PACS008")) {
                return false;
            }
        }
        
        // Check source message type for pacs.009 rules (bank-initiated)
        // This is implicit - if condition uses pacs.009.*, we should verify source is pacs.009
        boolean hasPacs009Condition = conds.keySet().stream().anyMatch(k -> k.startsWith("pacs.009."));
        if (hasPacs009Condition) {
            if (event.getSourceMessageType() == null || 
                !event.getSourceMessageType().getValue().equals("ISO20022_PACS009")) {
                return false;
            }
        }
        
        // Next bank CHIPS ID exists
        if (conds.containsKey("next_bank.chips_id_exists")) {
            Boolean expected = (Boolean) conds.get("next_bank.chips_id_exists");
            @SuppressWarnings("unchecked")
            Map<String, Object> nextBank = (Map<String, Object>) ctx.getOrDefault("next_bank", new HashMap<>());
            Boolean actual = (Boolean) nextBank.getOrDefault("chips_id_exists", false);
            if (!Objects.equals(expected, actual)) {
                return false;
            }
        }
        
        // Next bank ABA exists
        if (conds.containsKey("next_bank.aba_exists")) {
            Boolean expected = (Boolean) conds.get("next_bank.aba_exists");
            @SuppressWarnings("unchecked")
            Map<String, Object> nextBank = (Map<String, Object>) ctx.getOrDefault("next_bank", new HashMap<>());
            Boolean actual = (Boolean) nextBank.getOrDefault("aba_exists", false);
            if (!Objects.equals(expected, actual)) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Update routing context for service invocation rules (e.g., R2).
     */
    private void updateContextForServiceRule(RoutingRule rule, PaymentEvent event, 
                                           Map<String, Object> routingContext, 
                                           List<RoutingTrace> trace) {
        // For R2, update next_bank to creditor bank (both PACS.008 and PACS.009)
        if ("WF-PACS008-R2-US-INTERMEDIARY-LOOKUP".equals(rule.getRuleId()) ||
            "WF-PACS009-R2-US-INTERMEDIARY-LOOKUP".equals(rule.getRuleId())) {
            String creditorBic = event.getCreditorAgent() != null 
                ? event.getCreditorAgent().getIdValue() 
                : null;
            
            if (creditorBic != null && event.getEnrichmentContext() != null) {
                Map<String, Object> bicLookup = event.getEnrichmentContext().getBicLookup();
                if (bicLookup != null) {
                    Map<String, Object> nextBank = new HashMap<>();
                    nextBank.put("bic", creditorBic);
                    nextBank.put("chips_id_exists", bicLookup.containsKey("chips_uid") && bicLookup.get("chips_uid") != null);
                    nextBank.put("aba_exists", bicLookup.containsKey("aba_routing") && bicLookup.get("aba_routing") != null);
                    nextBank.put("country", bicLookup.get("country"));
                    routingContext.put("next_bank", nextBank);
                    
                    addTraceEntry(trace, rule.getRuleId(), "CONTEXT_UPDATED",
                        "Updated next_bank to creditor bank: " + creditorBic);
                }
            }
        }
    }
    
    /**
     * Apply rule actions and return evaluation result.
     */
    private RuleEvaluationResult applyRuleActions(RoutingRule rule, PaymentEvent event,
                                                 Map<String, Object> routingContext,
                                                 List<RoutingTrace> trace) {
        RoutingNetwork selectedNetwork = null;
        String decisionReason = "";
        
        // Evaluate decision matrix if present
        if (rule.getDecisionMatrix() != null) {
            selectedNetwork = evaluateDecisionMatrix(rule.getDecisionMatrix(), routingContext);
            decisionReason = "Decision matrix evaluated: selected " + 
                (selectedNetwork != null ? selectedNetwork.getValue() : "NONE");
        }
        
        // Otherwise, get network from actions
        if (selectedNetwork == null && rule.getActions() != null) {
            String networkStr = rule.getActions().getSelectedNetwork();
            if (networkStr != null) {
                try {
                    selectedNetwork = RoutingNetwork.valueOf(networkStr);
                    decisionReason = "Network selected from rule actions: " + networkStr;
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid network '{}' in rule {}, using SWIFT fallback", 
                        networkStr, rule.getRuleId());
                    selectedNetwork = RoutingNetwork.SWIFT;
                    decisionReason = "Invalid network '" + networkStr + "', using SWIFT fallback";
                }
            }
        }
        
        // Fallback to SWIFT if no network selected
        if (selectedNetwork == null) {
            selectedNetwork = RoutingNetwork.SWIFT;
            decisionReason = "No network specified, using SWIFT fallback";
        }
        
        addTraceEntry(trace, rule.getRuleId(), "APPLIED", decisionReason);
        
        // Build routing decision map
        Map<String, Object> routingDecision = new HashMap<>();
        routingDecision.put("selected_network", selectedNetwork.getValue());
        routingDecision.put("routing_rule_applied", rule.getRuleId());
        if (rule.getActions() != null) {
            routingDecision.put("routing_type", rule.getActions().getRoutingType());
            routingDecision.put("message_type", rule.getActions().getMessageType());
        }
        
        return RuleEvaluationResult.builder()
            .selectedNetwork(selectedNetwork)
            .appliedRuleId(rule.getRuleId())
            .appliedRulePriority(rule.getPriority())
            .appliedRuleDescription(rule.getDescription())
            .routingTrace(trace)
            .ruleApplied(true)
            .routingDecision(routingDecision)
            .build();
    }
    
    /**
     * Evaluate decision matrix for optimization rules.
     */
    private RoutingNetwork evaluateDecisionMatrix(DecisionMatrix matrix, Map<String, Object> ctx) {
        if (matrix.getIfConditions() != null) {
            for (DecisionCondition condition : matrix.getIfConditions()) {
                String conditionExpr = condition.getCondition();
                
                // Evaluate condition expressions
                if (evaluateConditionExpression(conditionExpr, ctx)) {
                    try {
                        return RoutingNetwork.valueOf(condition.getRoute());
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid network '{}' in decision matrix", condition.getRoute());
                    }
                }
            }
        }
        
        // Apply else clause
        if (matrix.getElseClause() != null && matrix.getElseClause().containsKey("route")) {
            try {
                return RoutingNetwork.valueOf(matrix.getElseClause().get("route"));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid network '{}' in decision matrix else clause", 
                    matrix.getElseClause().get("route"));
            }
        }
        
        return null;
    }
    
    /**
     * Evaluate a condition expression from decision matrix.
     * Supports AND conditions for combined checks.
     */
    private boolean evaluateConditionExpression(String expression, Map<String, Object> ctx) {
        // Handle combined AND conditions
        if (expression.contains(" AND ")) {
            String[] parts = expression.split(" AND ");
            boolean allMatch = true;
            for (String part : parts) {
                part = part.trim();
                if (!evaluateConditionExpression(part, ctx)) {
                    allMatch = false;
                    break;
                }
            }
            return allMatch;
        }
        
        // payment_ecosystem.chips_cutoff_passed == true
        if (expression.contains("payment_ecosystem.chips_cutoff_passed == true")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> ecosystem = (Map<String, Object>) ctx.getOrDefault("payment_ecosystem", new HashMap<>());
            return Boolean.TRUE.equals(ecosystem.get("chips_cutoff_passed"));
        }
        
        // payment_ecosystem.chips_queue_depth == HIGH
        if (expression.contains("payment_ecosystem.chips_queue_depth == HIGH")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> ecosystem = (Map<String, Object>) ctx.getOrDefault("payment_ecosystem", new HashMap<>());
            return "HIGH".equals(ecosystem.get("chips_queue_depth"));
        }
        
        // customer_preference == FED
        if (expression.contains("customer_preference == FED")) {
            return "FED".equals(ctx.get("customer_preference"));
        }
        
        // payment_urgency == HIGH
        if (expression.contains("payment_urgency == HIGH")) {
            return "HIGH".equals(ctx.get("payment_urgency"));
        }
        
        return false;
    }
    
    /**
     * Add a trace entry to the routing trace.
     */
    private void addTraceEntry(List<RoutingTrace> trace, String ruleId, String decision, String reason) {
        trace.add(RoutingTrace.builder()
            .ruleId(ruleId)
            .decision(decision)
            .reason(reason)
            .timestamp(Instant.now().toString())
            .build());
    }
}

