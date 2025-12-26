# Payment Execution Flow - How Payments Enter the System

## Overview

This document explains how payments enter the system and flow through the execution pipeline.

---

## Entry Points (Ingress Layer)

Payments can enter the system through **4 different rails**:

### 1. **SWIFT Ingress** (`SwiftIngressService`)
- **Input**: PACS.008 or PACS.009 XML messages
- **Source**: SWIFT network
- **Location**: `payment-ingress/src/main/java/com/wellsfargo/payment/ingress/swift/`

### 2. **FED Ingress** (`FedIngressService`)
- **Input**: PACS.009 XML messages
- **Source**: Federal Reserve Wire Network
- **Location**: `payment-ingress/src/main/java/com/wellsfargo/payment/ingress/`

### 3. **CHIPS Ingress** (`ChipsIngressService`)
- **Input**: PACS.009 XML messages
- **Source**: Clearing House Interbank Payments System
- **Location**: `payment-ingress/src/main/java/com/wellsfargo/payment/ingress/`

### 4. **Wells Ingress** (`WellsIngressService`)
- **Input**: PACS.008 XML messages
- **Source**: Payments originating within Wells Fargo (internally initiated)
- **Location**: `payment-ingress/src/main/java/com/wellsfargo/payment/ingress/`
- **Note**: This is for payments that START within Wells Fargo. Routing logic determines if they go out via FED/SWIFT or stay internal (IBT).

---

## Step-by-Step Execution Flow

### Step 1: Message Reception

**How it works:**
- Each ingress service listens to its respective Kafka topic or file system
- Messages arrive as **raw XML** (PACS.008 or PACS.009 format)

**Example (SWIFT Ingress):**
```java
// File-based processing
SwiftIngressService.processFile("path/to/pacs008.xml")

// OR Kafka-based processing
// Consumer listens to: payments.ingress.swift.in
```

### Step 2: Message Type Detection

**What happens:**
- The ingress service detects the message type (PACS.008 or PACS.009)
- Uses `MessageTypeDetector` to identify the XML structure

**Code Location:**
- `payment-ingress/src/main/java/com/wellsfargo/payment/ingress/swift/MessageTypeDetector.java`

**Example:**
```java
Iso20022MessageType messageType = MessageTypeDetector.detect(xmlContent);
// Returns: PACS_008 or PACS_009
```

### Step 3: XML to PaymentEvent Conversion

**What happens:**
- Raw XML is parsed and mapped to the **canonical PaymentEvent** model
- All rail-specific formats are converted to a unified structure

**Mappers:**
- `Pacs008Mapper.mapToPaymentEvent()` - Converts PACS.008 to PaymentEvent
- `Pacs009Mapper.mapToPaymentEvent()` - Converts PACS.009 to PaymentEvent

**Code Location:**
- `payment-ingress/src/main/java/com/wellsfargo/payment/ingress/swift/Pacs008Mapper.java`
- `payment-ingress/src/main/java/com/wellsfargo/payment/ingress/swift/Pacs009Mapper.java`

**Example PaymentEvent fields:**
```java
PaymentEvent {
    endToEndId: "E2E-123456789"
    msgId: "MSG-001"
    transactionId: "TXN-001"
    amount: 50000.00
    currency: "USD"
    debtor: { name: "Customer A", accountId: "ACC-123" }
    creditor: { name: "Customer B", accountId: "ACC-456" }
    debtorAgent: { idValue: "HDFCINBBXXX", name: "HDFC Bank" }
    creditorAgent: { idValue: "WFBIUS6SXXX", name: "Wells Fargo Bank" }
    direction: INBOUND
    sourceMessageType: ISO20022_PACS008
}
```

### Step 4: Publish to Orchestrator

**What happens:**
- The PaymentEvent is published to Kafka topic: **`payments.orchestrator.in`**
- Key: `endToEndId` (for partitioning)
- Value: `PaymentEvent` (serialized as JSON)

**Code Location:**
- `payment-ingress/src/main/java/com/wellsfargo/payment/ingress/swift/SwiftIngressService.java`
- Method: `publishToOrchestrator()`

**Example:**
```java
ProducerRecord<String, PaymentEvent> record = new ProducerRecord<>(
    "payments.orchestrator.in", 
    endToEndId, 
    paymentEvent
);
producer.send(record);
```

---

## Orchestrator Processing

### Step 5: Orchestrator Receives PaymentEvent

**What happens:**
- `PaymentOrchestratorService` consumes from `payments.orchestrator.in`
- Caches the PaymentEvent in memory (keyed by `endToEndId`)
- Sends initial status: **RCVD** (Received)

**Code Location:**
- `payment-orchestrator/src/main/java/com/wellsfargo/payment/orchestrator/PaymentOrchestratorService.java`
- Method: `handleIngressPayment()`

**Key Operations:**
1. **Idempotency Check**: Skip if payment already cached
2. **Cache PaymentEvent**: Store in `eventCache` map
3. **Send RCVD Status**: Notify sender that payment was received
4. **Publish to First Step**: Send to Account Validation

### Step 6: Sequential Processing Through Satellites

The orchestrator routes the payment through **5 satellite services** in sequence:

```
┌─────────────────────────────────────────────────────────┐
│ 1. Account Validation                                   │
│    Topic: payments.step.account_validation             │
│    → Enriches PaymentEvent with account details         │
│    → Publishes enriched PaymentEvent to routing topic   │
└─────────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────────┐
│ 2. Routing Validation                                   │
│    Topic: payments.step.routing_validation             │
│    → Determines payment rail (FED/CHIPS/SWIFT)          │
│    → Publishes routed PaymentEvent to sanctions topic   │
└─────────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────────┐
│ 3. Sanctions Check                                      │
│    Topic: payments.step.sanctions_check                 │
│    → Checks against OFAC/sanctions lists                │
│    → Returns PASS or FAIL                               │
└─────────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────────┐
│ 4. Balance Check                                        │
│    Topic: payments.step.balance_check                   │
│    → Verifies sufficient funds in debtor account        │
│    → Returns PASS or FAIL                               │
└─────────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────────┐
│ 5. Payment Posting                                      │
│    Topic: payments.step.payment_posting                 │
│    → Creates debit/credit ledger entries                │
│    → Persists to MongoDB                                │
│    → Returns PASS or FAIL                               │
└─────────────────────────────────────────────────────────┘
```

### Step 7: Service Result Processing

**What happens:**
- Each satellite service publishes a `ServiceResult` to its result topic
- Orchestrator consumes these results and routes to the next step

**Service Result Topics:**
- `service.results.account_validation`
- `service.results.routing_validation`
- `service.results.sanctions_check`
- `service.results.balance_check`
- `service.results.payment_posting`

**Code Location:**
- `PaymentOrchestratorService.handleServiceResult()`

**Routing Logic:**
```java
switch (serviceName) {
    case "account_validation":
        // Wait for routing_validation result
        // Send ACCP status
        break;
    case "routing_validation":
        // Wait for sanctions_check result
        // Send ACCC status
        break;
    case "sanctions_check":
        // Forward to balance_check
        publishToStep(TOPIC_BALANCE_CHECK_IN, event);
        // Send PDNG status
        break;
    case "balance_check":
        // Forward to payment_posting
        publishToStep(TOPIC_PAYMENT_POSTING_IN, event);
        // Send PDNG status
        break;
    case "payment_posting":
        // Payment complete!
        // Send ACSC status (Accepted Settlement Completed)
        publishFinalStatus(event);
        break;
}
```

### Step 8: Error Handling

**What happens if a step fails:**
- Satellite service publishes error to error topic: `service.errors.{service_name}`
- `ErrorManagementService` consumes errors and stores them
- Error appears in the **Error Management UI** for operator action

**Error Topics:**
- `service.errors.account_validation`
- `service.errors.routing_validation`
- `service.errors.sanctions_check`
- `service.errors.balance_check`
- `service.errors.payment_posting`

**Code Location:**
- `payment-orchestrator/src/main/java/com/wellsfargo/payment/orchestrator/error/ErrorManagementService.java`

### Step 9: Final Status

**What happens on success:**
- Payment posting completes successfully
- Orchestrator publishes final status to: `payments.final.status`
- Status: **ACSC** (Accepted Settlement Completed)
- Egress service consumes and sends to destination network

**Code Location:**
- `PaymentOrchestratorService.publishFinalStatus()`

---

## Complete Flow Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                    INGRESS LAYER                                │
│                                                                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐       │
│  │ SWIFT IN │  │  FED IN  │  │ CHIPS IN │  │  IBT IN  │       │
│  │ PACS.008 │  │ PACS.009  │  │ PACS.009  │  │ PACS.008 │       │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘       │
│       │             │             │             │               │
│       └─────────────┴─────────────┴─────────────┘               │
│                          │                                       │
│                          ▼                                       │
│              ┌───────────────────────┐                          │
│              │  XML to PaymentEvent   │                          │
│              │      Conversion        │                          │
│              └───────────┬───────────┘                          │
└──────────────────────────┼──────────────────────────────────────┘
                           │
                           ▼
              ┌────────────────────────────┐
              │  Kafka Topic:              │
              │  payments.orchestrator.in  │
              │  (Key: endToEndId)         │
              └────────────┬────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│              ORCHESTRATOR SERVICE                               │
│                                                                 │
│  1. Receive PaymentEvent                                        │
│  2. Cache in memory (endToEndId → PaymentEvent)                │
│  3. Send RCVD status                                            │
│  4. Publish to: payments.step.account_validation                │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│              SATELLITE SERVICES (Sequential)                    │
│                                                                 │
│  ┌──────────────────┐  ┌──────────────────┐                   │
│  │ Account          │→ │ Routing          │                   │
│  │ Validation       │  │ Validation       │                   │
│  │ (Enriches)       │  │ (Routes)         │                   │
│  └──────────────────┘  └──────────────────┘                   │
│                           │                                     │
│                           ▼                                     │
│  ┌──────────────────┐  ┌──────────────────┐                   │
│  │ Sanctions        │→ │ Balance          │                   │
│  │ Check            │  │ Check            │                   │
│  │ (Validates)      │  │ (Validates)      │                   │
│  └──────────────────┘  └──────────────────┘                   │
│                           │                                     │
│                           ▼                                     │
│  ┌──────────────────┐                                          │
│  │ Payment          │                                          │
│  │ Posting          │                                          │
│  │ (Posts)          │                                          │
│  └──────────────────┘                                          │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
              ┌────────────────────────────┐
              │  Kafka Topic:              │
              │  payments.final.status     │
              │  (Status: ACSC)            │
              └────────────┬────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                    EGRESS LAYER                                  │
│                                                                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐       │
│  │ SWIFT OUT│  │  FED OUT │  │CHIPS OUT │  │IBT SETTLE│       │
│  │ PACS.002 │  │ PACS.002  │  │ PACS.002  │  │ Notify   │       │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘       │
└─────────────────────────────────────────────────────────────────┘
```

---

## Key Kafka Topics

### Input Topics (Ingress → Orchestrator)
- **`payments.orchestrator.in`** - All payments enter here
  - Partitions: 20
  - Key: `endToEndId`
  - Value: `PaymentEvent` (JSON)

### Satellite Input Topics (Orchestrator → Satellites)
- **`payments.step.account_validation`** - First step
- **`payments.step.routing_validation`** - Routing decision
- **`payments.step.sanctions_check`** - Sanctions screening
- **`payments.step.balance_check`** - Balance verification
- **`payments.step.payment_posting`** - Final posting

### Service Result Topics (Satellites → Orchestrator)
- **`service.results.account_validation`**
- **`service.results.routing_validation`**
- **`service.results.sanctions_check`**
- **`service.results.balance_check`**
- **`service.results.payment_posting`**

### Error Topics (Satellites → Error Management)
- **`service.errors.account_validation`**
- **`service.errors.routing_validation`**
- **`service.errors.sanctions_check`**
- **`service.errors.balance_check`**
- **`service.errors.payment_posting`**

### Output Topics (Orchestrator → Egress)
- **`payments.final.status`** - Final payment status
- **`payments.notification`** - Status notifications (PACS.002)
- **`payments.return`** - Payment returns (PACS.004)

---

## Status Flow

As the payment progresses, status updates are sent via PACS.002:

1. **RCVD** (Received) - Payment received by orchestrator
2. **ACCP** (Accepted) - Account validation passed
3. **ACCC** (Accepted Customer Profile) - Routing validation passed
4. **PDNG** (Pending) - Pending balance check
5. **PDNG** (Pending) - Pending payment posting
6. **ACSC** (Accepted Settlement Completed) - Payment successfully posted

**Error Status:**
- **RJCT** (Rejected) - Payment rejected at any step
- **CANC** (Cancelled) - Payment cancelled

---

## Idempotency

**How it works:**
- Each payment is identified by `endToEndId` (unique identifier)
- Orchestrator caches PaymentEvent by `endToEndId`
- If same `endToEndId` arrives again, it's skipped (idempotent)
- Payment posting service also checks idempotency before posting

**Code Location:**
```java
// In PaymentOrchestratorService
if (eventCache.containsKey(endToEndId)) {
    log.debug("PaymentEvent already cached - skipping");
    return;
}
```

---

## State Management

**Orchestrator State:**
- **In-Memory Cache**: `Map<String, PaymentEvent> eventCache`
- Key: `endToEndId`
- Value: `PaymentEvent`
- Purpose: Track payment state through processing pipeline

**State Transitions:**
```
RECEIVED → ACCOUNT_VALIDATION → ROUTING_VALIDATION → 
SANCTIONS_CHECK → BALANCE_CHECK → PAYMENT_POSTING → COMPLETED
```

---

## Example: Complete Payment Journey

### 1. Payment Arrives (SWIFT)
```
File: test-data/sample/01_input_pacs008.xml
→ SwiftIngressService.processFile()
→ Detects: PACS.008
→ Maps to PaymentEvent
→ Publishes to: payments.orchestrator.in
```

### 2. Orchestrator Receives
```
PaymentOrchestratorService.handleIngressPayment()
→ Caches PaymentEvent (endToEndId: "E2E-001")
→ Sends RCVD status
→ Publishes to: payments.step.account_validation
```

### 3. Account Validation
```
AccountValidationService processes
→ Enriches PaymentEvent with account details
→ Publishes enriched PaymentEvent to routing_validation topic
→ Publishes ServiceResult: PASS
```

### 4. Orchestrator Routes
```
PaymentOrchestratorService.handleServiceResult()
→ Receives: account_validation PASS
→ Sends ACCP status
→ Waits for routing_validation result
```

### 5. Routing Validation
```
RoutingValidationService processes
→ Determines rail: FED
→ Publishes routed PaymentEvent to sanctions_check topic
→ Publishes ServiceResult: PASS
```

### 6. Continue Through Pipeline...
```
→ Sanctions Check: PASS
→ Balance Check: PASS
→ Payment Posting: PASS
→ Final Status: ACSC
→ Egress sends to destination
```

---

## Error Scenarios

### Scenario 1: Account Validation Fails
```
AccountValidationService
→ Publishes ServiceResult: FAIL
→ Publishes Error to: service.errors.account_validation
→ ErrorManagementService stores error
→ Error appears in UI
→ Operator can Fix & Resume or Cancel
```

### Scenario 2: Sanctions Check Fails
```
SanctionsCheckService
→ Publishes ServiceResult: FAIL
→ Publishes Error to: service.errors.sanctions_check
→ ErrorManagementService stores error
→ Error appears in UI with CRITICAL severity
→ Operator should Cancel & Return (PACS.004)
```

### Scenario 3: Balance Check Fails
```
BalanceCheckService
→ Publishes ServiceResult: FAIL
→ Publishes Error to: service.errors.balance_check
→ ErrorManagementService stores error
→ Error appears in UI
→ Operator can Restart (after customer deposits funds)
```

---

## Summary

**Payment Entry Flow:**
1. **Ingress** receives raw message (XML)
2. **Convert** to canonical PaymentEvent
3. **Publish** to `payments.orchestrator.in`
4. **Orchestrator** receives and caches
5. **Route** through 5 satellite services sequentially
6. **Process** each step (validate, enrich, post)
7. **Handle** errors if any step fails
8. **Complete** with final status (ACSC)
9. **Egress** sends to destination network

**Key Points:**
- All payments enter through **one topic**: `payments.orchestrator.in`
- Orchestrator is the **central coordinator**
- Satellites process **sequentially** (not in parallel)
- Errors are **captured and stored** for operator action
- State is **tracked in memory** by endToEndId
- **Idempotency** ensures no duplicate processing

---

**For more details, see:**
- `ARCHITECTURE.md` - Overall system architecture
- `KAFKA_TOPICS.md` - Kafka topic configuration
- `ROUTING_LOGIC_FLOW.md` - Routing validation details

