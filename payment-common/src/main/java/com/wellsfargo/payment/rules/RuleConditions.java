package com.wellsfargo.payment.rules;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Rule conditions - flexible map to support various condition types.
 * 
 * Supports conditions like:
 * - mt103.credit_agent_bic
 * - mt103.intermediary_bic
 * - ultimate_creditor_country
 * - ultimate_creditor_country_not
 * - next_bank.chips_id_exists
 * - next_bank.aba_exists
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleConditions {
    @Builder.Default
    private Map<String, Object> conditions = new HashMap<>();
    
    @JsonAnySetter
    public void setCondition(String key, Object value) {
        if (conditions == null) {
            conditions = new HashMap<>();
        }
        conditions.put(key, value);
    }
    
    public Object getCondition(String key) {
        return conditions != null ? conditions.get(key) : null;
    }
    
    public boolean hasCondition(String key) {
        return conditions != null && conditions.containsKey(key);
    }
    
    public Map<String, Object> getConditions() {
        return conditions;
    }
    
    public void setConditions(Map<String, Object> conditions) {
        this.conditions = conditions;
    }
}

