# Routing Validation Service Implementation

## Overview

The Routing Validation Service has been implemented based on the SwiftProcessing reference project. It evaluates routing rules in priority order, selects the appropriate network (IBT/FED/CHIPS/SWIFT), handles agent insertion/substitution, and maintains a routing trace for auditability.

## Implementation Details

### Files Created/Updated

1. **`canonical/models.py`** - Updated `RoutingContext`
   - Added `routing_trace` field to track routing decisions
   - Routing trace contains: rule_id, decision, reason, timestamp

2. **`satellites/routing_validation.py`** - Routing validation service
   - Loads routing rules from `config/routing_rulesV2.json`
   - Evaluates rules in priority order
   - Applies routing decisions
   - Maintains routing trace

3. **`config/routing_rulesV2.json`** - Routing rules configuration
   - Copied from SwiftProcessing project
   - Contains 6 routing rules with priorities

## Service Responsibilities

1. **Consume PaymentEvent**: Receives enriched PaymentEvent (with account_validation data)
2. **Load Routing Rules**: Loads `routing_rulesV2.json` at startup
3. **Evaluate Rules**: Evaluates rules in priority order (lower number = higher priority)
4. **Select Network**: Selects one of: INTERNAL (IBT), FED, CHIPS, or SWIFT
5. **Insert/Substitute Agents**: Handles intermediary agent insertion or substitution
6. **Append Routing Trace**: Maintains trace of all rule evaluations with:
   - `rule_id`: Rule that was evaluated
   - `decision`: Decision made (MATCHED, SKIPPED, APPLIED, SERVICES_INVOKED, etc.)
   - `reason`: Explanation of the decision
   - `timestamp`: ISO 8601 timestamp

## Routing Trace Structure

Each routing trace entry contains:

```python
{
    "rule_id": "WF-MT103-R1-INTERNAL-CREDIT",
    "decision": "APPLIED",
    "reason": "Network selected from rule actions: INTERNAL",
    "timestamp": "2025-12-23T13:36:00.000Z"
}
```

### Decision Types

- **MATCHED**: Rule conditions matched, rule will be applied
- **SKIPPED**: Rule conditions did not match, rule skipped
- **APPLIED**: Rule was successfully applied with routing decision
- **SERVICES_INVOKED**: Rule only invokes services (like R2), continues to next rule
- **CONTEXT_UPDATED**: Rule updated routing context (like R2 updating next_bank)
- **NO_RULE_MATCHED**: No rule matched (fallback scenario)

## Rule Evaluation Flow

```
1. Load routing rules (sorted by priority)
   ↓
2. For each rule in priority order:
   a. Build routing context from PaymentEvent
   b. Match rule conditions
   c. If matched:
      - Add trace entry (MATCHED)
      - Check if rule only invokes services
      - If services only: continue to next rule
      - Otherwise: apply rule actions
   d. If not matched:
      - Add trace entry (SKIPPED)
      - Continue to next rule
   ↓
3. Apply rule actions:
   - Evaluate decision_matrix (if present)
   - Select network
   - Insert/substitute agents (if required)
   - Build routing context
   - Add trace entry (APPLIED)
   ↓
4. Return enriched PaymentEvent with routing_context
```

## Network Selection

The service selects networks based on:

1. **Rule Actions**: Direct `selected_network` in rule actions
2. **Decision Matrix**: For optimization rules (R5), evaluates conditions:
   - CHIPS cutoff passed → FED
   - CHIPS queue depth HIGH → FED
   - Customer preference FED → FED
   - Payment urgency HIGH → FED
   - Otherwise → CHIPS (default)

## Agent Insertion/Substitution

The service supports:

1. **Insert Intermediary**: Adds intermediary agent to agent_chain
   - Supports template variables: `${account_validation.preferred_correspondent}`
   - Inserts at beginning of agent_chain

2. **Substitute Intermediary**: Replaces existing intermediary agent
   - Supports template variables
   - Replaces first agent in agent_chain or creates new chain

## Routing Rules Supported

1. **R1**: INTERNAL - If creditor is Wells Fargo
2. **R2**: INTERMEDIARY_LOOKUP - Wells is intermediary, lookup next bank capabilities
3. **R3**: CHIPS_DEFAULT - Route via CHIPS when CHIPS ID available
4. **R4**: FED_ONLY - Route via FED when only ABA available
5. **R5**: OPTIMIZATION - Apply optimization when both CHIPS and FED available
6. **R6**: SWIFT - Cross-border payments via SWIFT

## Kafka Topics

- **Input Topic**: `payments.step.routing_validation`
- **Result Topic**: `service.results.routing_validation`
- **Error Topic**: `service.errors.routing_validation`
- **Sanctions Topic**: `payments.step.sanctions_check` (routed PaymentEvent)

## Orchestrator Sequencing

The service maintains orchestrator sequencing by:
1. Publishing `ServiceResult` to the result topic (orchestrator consumes this)
2. Publishing routed `PaymentEvent` to sanctions check topic (next satellite consumes this)

**Note**: Orchestrator sequencing is NOT changed - the service follows the same pattern as the reference implementation.

## Example Routing Trace

```json
[
  {
    "rule_id": "WF-MT103-R1-INTERNAL-CREDIT",
    "decision": "SKIPPED",
    "reason": "Rule conditions did not match",
    "timestamp": "2025-12-23T13:36:00.000Z"
  },
  {
    "rule_id": "WF-MT103-R2-US-INTERMEDIARY-LOOKUP",
    "decision": "SERVICES_INVOKED",
    "reason": "Rule only invokes services, continuing to next rule",
    "timestamp": "2025-12-23T13:36:00.100Z"
  },
  {
    "rule_id": "WF-MT103-R2-US-INTERMEDIARY-LOOKUP",
    "decision": "CONTEXT_UPDATED",
    "reason": "Updated next_bank to creditor bank: CHASUS33XXX",
    "timestamp": "2025-12-23T13:36:00.100Z"
  },
  {
    "rule_id": "WF-MT103-R3-CHIPS-DEFAULT",
    "decision": "MATCHED",
    "reason": "Rule conditions matched: Route via CHIPS when CHIPS ID is available (default, cheaper option)",
    "timestamp": "2025-12-23T13:36:00.200Z"
  },
  {
    "rule_id": "WF-MT103-R3-CHIPS-DEFAULT",
    "decision": "APPLIED",
    "reason": "Network selected from rule actions: CHIPS",
    "timestamp": "2025-12-23T13:36:00.200Z"
  }
]
```

## Java Implementation Notes

The Python implementation serves as a reference. For Java implementation:

1. Convert dataclasses to Java classes (using Lombok or manual)
2. Implement JSON parsing for routing rules (Jackson, Gson, etc.)
3. Implement Kafka consumer/producer using Kafka Java client
4. Implement JSON serialization/deserialization for PaymentEvent
5. Replace print statements with proper logging (SLF4J, Log4j)
6. Add unit tests for rule evaluation logic
7. Add integration tests for end-to-end routing

## Testing

To test the service:

1. Create a PaymentEvent with enrichment_context.account_validation
2. Call `RoutingValidationService._handle_event(event)`
3. Verify:
   - ServiceResult is created with correct status
   - Routed PaymentEvent has `routing_context.selected_network` set
   - `routing_context.routing_rule_applied` contains the applied rule ID
   - `routing_context.routing_trace` contains trace entries
   - Agent chain is updated if agents are inserted/substituted

