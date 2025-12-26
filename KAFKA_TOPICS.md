# Kafka Topics - Complete List

This document lists all Kafka topics used in the Wells Payment Engine for end-to-end testing from ingress to egress.

## Topic Naming Convention

- **Ingress topics**: `ingress.{rail}.in` - External messages coming into the system
- **Orchestrator topics**: `payments.orchestrator.in` - Canonical PaymentEvent from ingress
- **Step topics**: `payments.step.{service}` - PaymentEvent input to satellite services
- **Result topics**: `service.results.{service}` - ServiceResult output from satellite services
- **Error topics**: `service.errors.{service}` - Error/FAIL ServiceResult from satellite services
- **Final status**: `payments.final.status` - Final PaymentEvent after all processing
- **Egress topics**: `egress.{rail}.out` - Outbound messages to external systems

## Complete Topic List

### 1. Ingress Topics (Optional - for testing with Kafka consumers)

These topics are used by ingress services that consume from Kafka (not the CLI-based SwiftIngressService):

```
ingress.swift.in          # SWIFT messages (PACS.008/PACS.009)
ingress.fed.in            # FED messages (PACS.008/PACS.009)
ingress.chips.in          # CHIPS messages (PACS.008/PACS.009)
ingress.ibt.in            # IBT messages (PACS.008)
```

**Note**: The CLI-based `SwiftIngressService` publishes directly to `payments.orchestrator.in` and does NOT consume from `ingress.swift.in`.

---

### 2. Orchestrator Topics

```
payments.orchestrator.in  # Input: Canonical PaymentEvent from ingress services
                          # Published by: Ingress services (SwiftIngressService CLI, etc.)
                          # Consumed by: PaymentOrchestratorService
```

---

### 3. Step Topics (PaymentEvent Input to Satellites)

```
payments.step.account_validation    # Input to AccountValidationService
payments.step.routing_validation   # Input to RoutingValidationService (published by AccountValidationService)
payments.step.sanctions_check       # Input to SanctionsCheckService (published by RoutingValidationService)
payments.step.balance_check         # Input to BalanceCheckService (published by Orchestrator)
payments.step.payment_posting       # Input to PaymentPostingService (published by Orchestrator)
```

---

### 4. Service Result Topics (ServiceResult Output from Satellites)

```
service.results.account_validation  # Output from AccountValidationService
service.results.routing_validation # Output from RoutingValidationService
service.results.sanctions_check     # Output from SanctionsCheckService
service.results.balance_check       # Output from BalanceCheckService
service.results.payment_posting     # Output from PaymentPostingService
```

**Consumed by**: PaymentOrchestratorService (routes to next step based on results)

---

### 5. Service Error Topics (Error/FAIL ServiceResult)

```
service.errors.account_validation  # Errors from AccountValidationService
service.errors.routing_validation  # Errors from RoutingValidationService
service.errors.sanctions_check     # Errors from SanctionsCheckService
service.errors.balance_check       # Errors from BalanceCheckService
service.errors.payment_posting     # Errors from PaymentPostingService
```

**Note**: These topics are for monitoring/debugging. The orchestrator does not consume from them.

---

### 6. Final Status Topic

```
payments.final.status              # Final PaymentEvent after all processing succeeds
                                  # Published by: PaymentOrchestratorService
                                  # Consumed by: All Egress services
```

---

### 7. Notification Topic

```
payments.notification              # PACS.002 status reports to instructing bank
                                  # Published by: NotificationService (via orchestrator)
                                  # Consumed by: Egress services (for SWIFT delivery)
```

**Purpose**: Sends payment status updates (RCVD, ACCP, ACCC, PDNG, ACSP, ACSC, RJCT, CANC) 
to the instructing bank via PACS.002 messages.

---

### 8. Payment Return Topic

```
payments.return                    # PACS.004 payment return messages
                                  # Published by: NotificationService (via ErrorManagementService)
                                  # Consumed by: Egress services (for SWIFT delivery)
```

**Purpose**: Sends PACS.004 payment return messages when a payment is cancelled and returned 
to the instructing bank. Includes ISO 20022 return reason codes (AC01, AC04, AC06, AM04, AM05, 
BE04, BE05, NARR, RC01, RR01, RR02, AG01, AG02).

**Return Reason Codes**:
- **AC01**: Account identifier incorrect
- **AC04**: Account closed
- **AC06**: Account blocked
- **AM04**: Insufficient funds
- **AM05**: Duplicate payment
- **BE04**: Incorrect creditor name
- **BE05**: Creditor missing
- **NARR**: Narrative reason (free-text)
- **RC01**: Invalid bank identifier
- **RR01**: Regulatory reason (sanctions/AML)
- **RR02**: Regulatory reporting incomplete
- **AG01**: Transaction forbidden
- **AG02**: Invalid bank operation

**Note**: When a PACS.004 is published, a corresponding RJCT PACS.002 status is also sent 
to indicate the payment rejection.

---

### 9. Cancellation Topic

```
payments.cancellation.in           # camt.056 cancellation requests
                                  # Published by: Ingress services (when camt.056 received)
                                  # Consumed by: CancellationHandler
```

**Purpose**: Receives cancellation requests from instructing banks via camt.056 messages.

---

### 9. Egress Topics (Outbound Messages)

```
egress.swift.out                   # SWIFT outbound messages (PACS.009)
egress.fed.out                     # FED outbound messages (PACS.009)
egress.chips.out                   # CHIPS outbound messages (PACS.009)
egress.ibt.settlement              # IBT settlement messages
```

**Published by**: Respective egress services (SwiftEgressService, FedEgressService, etc.)

---

## Payment Flow Through Topics

```
1. Ingress (CLI)
   └─> payments.orchestrator.in

2. Orchestrator
   └─> payments.step.account_validation

3. Account Validation
   ├─> service.results.account_validation
   └─> payments.step.routing_validation (enriched PaymentEvent)

4. Routing Validation
   ├─> service.results.routing_validation
   └─> payments.step.sanctions_check (routed PaymentEvent)

5. Sanctions Check
   ├─> service.results.sanctions_check
   └─> (Orchestrator routes to next step)

6. Orchestrator (after sanctions check)
   └─> payments.step.balance_check

7. Balance Check
   ├─> service.results.balance_check
   └─> (Orchestrator routes to next step)

8. Orchestrator (after balance check)
   └─> payments.step.payment_posting

9. Payment Posting
   ├─> service.results.payment_posting
   └─> (Orchestrator emits final status)

10. Orchestrator (after payment posting)
    └─> payments.final.status

11. Egress Services
    └─> egress.{rail}.out (based on routing decision)

12. Status Notifications (throughout processing)
    └─> payments.notification (PACS.002 status updates)
        - RCVD: Payment received
        - ACCP: Accepted for processing
        - ACCC: Accepted after customer validation
        - PDNG: Pending further checks
        - ACSP: Accepted, settlement in process
        - ACSC: Accepted, settlement completed
        - RJCT: Rejected
        - CANC: Cancelled (via camt.056)

13. Cancellation Requests
    └─> payments.cancellation.in (camt.056 requests)
        └─> CancellationHandler processes and sends CANC status

14. Payment Returns (Error Management UI)
    └─> payments.return (PACS.004 payment return messages)
        └─> Published when payment is cancelled via Error Management UI
        └─> Includes ISO 20022 return reason code (AC01, RR01, etc.)
        └─> Also sends RJCT PACS.002 status notification
```

---

## Topic Configuration Recommendations

### Partitions
- **Recommended**: 20 partitions per topic
- **Reason**: Supports 400 TPS target (20 TPS per partition)

### Replication Factor
- **Recommended**: 3 (for production)
- **Development**: 1 (single broker)

### Key Strategy
- **Key**: `endToEndId` (String)
- **Reason**: Ensures same payment always goes to same partition, maintaining ordering

### Retention
- **Recommended**: 7 days (for debugging)
- **Development**: 1 day (for testing)

---

## Kafka Setup Script (Example)

For local testing, create topics with:

```bash
# Bootstrap server
BOOTSTRAP_SERVERS=localhost:9092

# Create orchestrator topic
kafka-topics.sh --create \
  --bootstrap-server $BOOTSTRAP_SERVERS \
  --topic payments.orchestrator.in \
  --partitions 20 \
  --replication-factor 1

# Create step topics
for topic in account_validation routing_validation sanctions_check balance_check payment_posting; do
  kafka-topics.sh --create \
    --bootstrap-server $BOOTSTRAP_SERVERS \
    --topic payments.step.$topic \
    --partitions 20 \
    --replication-factor 1
done

# Create result topics
for topic in account_validation routing_validation sanctions_check balance_check payment_posting; do
  kafka-topics.sh --create \
    --bootstrap-server $BOOTSTRAP_SERVERS \
    --topic service.results.$topic \
    --partitions 20 \
    --replication-factor 1
done

# Create error topics
for topic in account_validation routing_validation sanctions_check balance_check payment_posting; do
  kafka-topics.sh --create \
    --bootstrap-server $BOOTSTRAP_SERVERS \
    --topic service.errors.$topic \
    --partitions 20 \
    --replication-factor 1
done

# Create final status topic
kafka-topics.sh --create \
  --bootstrap-server $BOOTSTRAP_SERVERS \
  --topic payments.final.status \
  --partitions 20 \
  --replication-factor 1

# Create egress topics
for topic in swift.out fed.out chips.out ibt.settlement; do
  kafka-topics.sh --create \
    --bootstrap-server $BOOTSTRAP_SERVERS \
    --topic egress.$topic \
    --partitions 20 \
    --replication-factor 1
done
```

---

## Summary

**Total Topics Required**: 27 topics

- 1 Orchestrator input topic
- 5 Step topics
- 5 Result topics
- 5 Error topics
- 1 Final status topic
- 1 Notification topic (PACS.002 status reports)
- 1 Payment return topic (PACS.004 payment returns)
- 1 Cancellation topic (camt.056 requests)
- 4 Egress topics
- 4 Ingress topics (optional, for Kafka-based ingress testing)

**Minimum Topics for Full Cycle Testing**: 24 topics (excluding optional ingress topics)

---

## Notes

1. **Sanctions Check Service**: The topic `payments.step.sanctions_check` is referenced but the service may not be fully implemented. The orchestrator expects results from this service.

2. **CLI Ingress**: The `SwiftIngressService` CLI tool publishes directly to `payments.orchestrator.in` and does not use `ingress.swift.in`.

3. **Topic Creation**: Topics are not auto-created. They must be created before starting services.

4. **Consumer Groups**: Each service uses a dedicated consumer group (e.g., `account-validation-group`, `routing-validation-group`).

