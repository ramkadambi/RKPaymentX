# Return of Funds Implementation - Two-Layer Process

## Overview

This document describes the implementation of the return of funds process for post-settlement cancellations, following the ISO 20022 two-layer approach:

1. **Investigation Layer (The "Talk")**: camt.056 → camt.029 (decision)
2. **Settlement Layer (The "Action")**: pacs.004 (actual fund return)

## Architecture

### Components Created

1. **Camt029Generator** (`payment-common`)
   - Generates camt.029 Resolution of Investigation messages
   - Supports three resolution statuses: RJCT (refused), PDCR (pending), ACCP (approved)

2. **CancellationCase** (`payment-common`)
   - Data model for tracking cancellation investigations
   - Tracks case status: PENDING, PDCR, REFUSED, APPROVED, RETURNED

3. **CaseManagementService** (`payment-orchestrator`)
   - Manages cancellation cases throughout their lifecycle
   - Tracks investigation and settlement phases

4. **Enhanced CancellationHandler** (`payment-orchestrator`)
   - Implements two-layer process
   - Handles both unsettled and settled payment cancellations
   - Routes to investigation or direct cancellation based on payment status

5. **CancellationMessageDetector** (`payment-ingress`)
   - Detects camt.055 and camt.056 messages in ingress
   - Routes cancellation messages to appropriate handler

6. **Enhanced Pacs004Generator** (`payment-common`)
   - Supports fees and settlement dates
   - Generates complete pacs.004 with original transaction reference

7. **Enhanced NotificationService** (`payment-common`)
   - Publishes camt.029 messages
   - Publishes pacs.004 with fees support

## Process Flow

### Scenario: Post-Settlement Cancellation

```
1. Customer/Bank sends camt.056 cancellation request
   ↓
2. Ingress Service (SWIFT/FED/CHIPS/WELLS) detects cancellation message
   ↓
3. Routes to payments.cancellation.in topic
   ↓
4. CancellationHandler receives camt.056
   ↓
5. Creates CancellationCase for tracking
   ↓
6. Checks payment status:
   - If NOT SETTLED → Direct cancellation (CANC status via PACS.002)
   - If SETTLED → Two-layer process begins
   ↓
7. INVESTIGATION LAYER:
   - Determines refund resolution (RJCT/PDCR/ACCP)
   - Generates camt.029 (Resolution of Investigation)
   - Publishes camt.029 to payments.notification topic
   ↓
8. If APPROVED → SETTLEMENT LAYER:
   - Calculates return amount (original - fees)
   - Generates pacs.004 (Payment Return)
   - Publishes pacs.004 to payments.return topic
   - Updates case status to RETURNED
```

## Message Types

### camt.055 (Customer Payment Cancellation Request)
- **Sender**: Customer (debtor)
- **Receiver**: Debtor Bank
- **Purpose**: Customer requests cancellation
- **Handling**: Converted to camt.056 by debtor bank

### camt.056 (FI-to-FI Payment Cancellation Request)
- **Sender**: Debtor Bank
- **Receiver**: Creditor Bank (Wells Fargo)
- **Purpose**: Bank requests cancellation from another bank
- **Handling**: Processed by CancellationHandler

### camt.029 (Resolution of Investigation)
- **Sender**: Creditor Bank (Wells Fargo)
- **Receiver**: Debtor Bank
- **Purpose**: Provides decision on cancellation request
- **Status Codes**:
  - **RJCT**: Refused (e.g., goods already shipped)
  - **PDCR**: Pending Cancellation Request (waiting for customer approval)
  - **ACCP**: Approved (will proceed to settlement)

### pacs.004 (Payment Return)
- **Sender**: Creditor Bank (Wells Fargo)
- **Receiver**: Debtor Bank
- **Purpose**: Actually returns funds (value message)
- **Features**:
  - Includes return amount (may be less than original if fees deducted)
  - Includes settlement date
  - Links to original payment via UETR/EndToEndId

## Key Features

### 1. Universal Ingress Support
- All ingress services (SWIFT, FED, CHIPS, Wells) detect and route cancellation messages
- Same process regardless of ingress channel

### 2. Case Tracking
- Each cancellation creates a case with unique Case ID
- Tracks investigation and settlement phases
- Links all messages via UETR/EndToEndId

### 3. Fee Handling
- Supports processing fees in pacs.004
- Return amount = Original amount - Fees
- Configurable via `cancellation.return.fee` property

### 4. Business Rules
- Maps cancellation reason codes to return reason codes
- Determines refund resolution based on business rules
- Supports manual review (PDCR status)

## Configuration

### Application Properties

```properties
# Cancellation return fee (default: 0.00)
cancellation.return.fee=0.00

# Kafka topics
payments.cancellation.in=payments.cancellation.in
payments.notification=payments.notification
payments.return=payments.return
```

## Kafka Topics

### payments.cancellation.in
- **Type**: Consumer (CancellationHandler)
- **Content**: camt.056 XML messages
- **Key**: EndToEndId (or generated key)
- **Purpose**: Receives cancellation requests from all ingress channels

### payments.notification
- **Type**: Producer (NotificationService)
- **Content**: PACS.002 and camt.029 XML messages
- **Key**: EndToEndId
- **Purpose**: Status updates and investigation resolutions

### payments.return
- **Type**: Producer (NotificationService)
- **Content**: PACS.004 XML messages
- **Key**: EndToEndId
- **Purpose**: Payment return messages (actual fund movement)

## Code Structure

```
payment-common/
  ├── notification/
  │   ├── Camt029Generator.java          # camt.029 generator
  │   ├── Pacs004Generator.java          # Enhanced with fees
  │   └── NotificationService.java       # Enhanced with camt.029 support
  └── cancellation/
      └── CancellationCase.java          # Case data model

payment-orchestrator/
  ├── CancellationHandler.java           # Two-layer process handler
  └── cancellation/
      └── CaseManagementService.java     # Case management

payment-ingress/
  ├── BaseIngressService.java            # Enhanced with cancellation routing
  ├── SwiftIngressService.java           # Updated to detect cancellations
  ├── FedIngressService.java             # Updated to detect cancellations
  ├── ChipsIngressService.java           # Updated to detect cancellations
  ├── WellsIngressService.java           # Updated to detect cancellations
  └── common/
      └── CancellationMessageDetector.java  # Message type detection
```

## Testing

### Test Scenarios

1. **Unsettled Payment Cancellation**
   - Send camt.056 for payment not yet settled
   - Expected: Direct cancellation (CANC status via PACS.002)

2. **Settled Payment - Refund Approved**
   - Send camt.056 for settled payment
   - Business rule approves refund
   - Expected: camt.029 (ACCP) → pacs.004

3. **Settled Payment - Refund Refused**
   - Send camt.056 for settled payment
   - Business rule refuses refund
   - Expected: camt.029 (RJCT)

4. **Settled Payment - Pending Review**
   - Send camt.056 for settled payment
   - Requires manual review
   - Expected: camt.029 (PDCR)

5. **Cross-Channel Cancellation**
   - Send camt.056 via different ingress channels
   - Expected: Same process regardless of channel

## Important Notes

1. **UETR Linkage**: All messages maintain traceability via UETR/EndToEndId
2. **Settlement Date**: pacs.004 includes its own settlement date
3. **Fees**: Can be deducted from return amount in pacs.004
4. **Case Management**: In-memory storage (production should use database)
5. **Business Rules**: Currently simple logic (production should integrate with business rule engine)

## Future Enhancements

1. Database-backed case management
2. Integration with business rule engine
3. Customer/merchant approval workflow for PDCR
4. Automated fee calculation based on payment type
5. Audit trail for all cancellation activities
6. Dashboard for case monitoring

