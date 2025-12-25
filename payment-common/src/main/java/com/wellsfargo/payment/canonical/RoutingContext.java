package com.wellsfargo.payment.canonical;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wellsfargo.payment.canonical.enums.RoutingNetwork;
import com.wellsfargo.payment.canonical.enums.Urgency;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Routing decision context and metadata.
 * 
 * Contains all information related to routing decisions:
 * - Selected network
 * - Routing rule applied
 * - Intermediary agents
 * - Routing decision details
 * - Payment ecosystem context
 * 
 * All routing decisions are expressed via this context.
 * This class is rail-agnostic - no rail-specific fields are included.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RoutingContext {
    /**
     * Selected payment routing network.
     */
    private RoutingNetwork selectedNetwork;

    /**
     * Rule ID that was applied.
     */
    private String routingRuleApplied;

    /**
     * Priority of the routing rule that was applied.
     */
    private Integer routingRulePriority;

    /**
     * Ordered list of intermediary agents in the payment chain.
     */
    private List<Agent> agentChain;

    /**
     * Routing decision details.
     * Structure: {
     *   "selected_network": RoutingNetwork,
     *   "sender_bank": Agent,
     *   "creditor_bank": Agent,
     *   "intermediary_bank": Agent (optional),
     *   "urgency": Urgency,
     *   "customer_preference": RoutingNetwork (optional),
     *   "routing_rule_applied": String (optional)
     * }
     */
    private Map<String, Object> routingDecision;

    /**
     * Payment ecosystem context (for optimization rules).
     * Structure: {
     *   "chips_cutoff_passed": boolean,
     *   "chips_queue_depth": String (e.g., "NORMAL", "HIGH"),
     *   "fed_cutoff_passed": boolean,
     *   "swift_cutoff_passed": boolean
     * }
     */
    private Map<String, Object> paymentEcosystem;

    /**
     * Customer preference for routing network.
     */
    private RoutingNetwork customerPreference;

    /**
     * Payment urgency level.
     */
    @Builder.Default
    private Urgency paymentUrgency = Urgency.NORMAL;

    /**
     * Next bank information (for routing decisions).
     * Structure: {
     *   "bic": String,
     *   "chips_id_exists": boolean,
     *   "aba_exists": boolean,
     *   "country": String
     * }
     */
    private Map<String, Object> nextBank;

    /**
     * Routing trace - explains routing decisions made.
     * Each entry represents a routing rule evaluation or decision point.
     */
    private List<RoutingTrace> routingTrace;
}

