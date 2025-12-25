package com.wellsfargo.payment.canonical;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Routing decision trace entry.
 * 
 * Used to explain routing decisions made during payment processing.
 * Each entry represents a single routing rule evaluation or decision point.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RoutingTrace {
    /**
     * Rule ID that was evaluated.
     */
    private String ruleId;

    /**
     * Decision made (e.g., "MATCHED", "SKIPPED", "APPLIED").
     */
    private String decision;

    /**
     * Explanation of the decision.
     */
    private String reason;

    /**
     * ISO 8601 timestamp of when the decision was made.
     */
    private String timestamp;
}

