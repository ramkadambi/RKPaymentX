package com.wellsfargo.payment.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Decision matrix for optimization rules (e.g., R5).
 * 
 * Evaluates a series of if conditions in order, and if none match,
 * applies the else clause.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecisionMatrix {
    @JsonProperty("if")
    private List<DecisionCondition> ifConditions;
    
    @JsonProperty("else")
    private Map<String, String> elseClause;
}

