package com.wellsfargo.payment.rules;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single condition in a decision matrix.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecisionCondition {
    private String condition;
    
    private String route;
}

