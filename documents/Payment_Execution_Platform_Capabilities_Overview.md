# Payment Execution Platform - Capabilities Overview

**Document Version:** 1.0  
**Date:** December 2025  
**Audience:** Senior Management

---

## Executive Summary

The Payment Execution Platform is a comprehensive, ISO 20022-compliant payment processing system that handles inbound payments from multiple networks (SWIFT, FED, CHIPS), processes them through automated validation and compliance checks, routes them intelligently to the appropriate settlement network, and provides real-time status confirmations. The platform also supports post-settlement fund return processes with a sophisticated two-layer investigation and settlement mechanism.

### Key Capabilities

- **Multi-Network Inbound Processing**: Accepts payments from SWIFT, FED, CHIPS, and internal Wells Fargo channels
- **Intelligent Routing**: Automatically determines optimal payment rail (FED, CHIPS, SWIFT, or Internal) based on account validation and payment characteristics
- **Real-Time Status Reporting**: Provides immediate confirmations (PACS.002) to instructing banks at every processing stage
- **Post-Settlement Fund Returns**: Handles cancellation requests with investigation and settlement layers
- **Comprehensive Error Management**: Operator-friendly UI for monitoring and resolving payment exceptions

---

## 1. SWIFT Inbound Payment Processing

### 1.1 Payment Reception

The platform accepts two types of ISO 20022 payment messages from the SWIFT network:

#### PACS.008 (Customer Credit Transfer)
- **Source**: Customer-initiated payments from external banks
- **Purpose**: Transfers funds from a customer's account at an external bank to a Wells Fargo customer
- **Example Scenario**: A customer at HDFC Bank (India) sends money to a Wells Fargo customer in the US

#### PACS.009 (Financial Institution Credit Transfer)
- **Source**: Bank-to-bank transfers from external financial institutions
- **Purpose**: Interbank settlement transactions
- **Example Scenario**: A foreign bank settles a batch of customer payments to Wells Fargo

### 1.2 Message Processing Pipeline

Upon receipt of a SWIFT payment message, the platform processes it through the following automated stages:

```
┌─────────────────────────────────────────────────────────────┐
│                    SWIFT INBOUND FLOW                       │
└─────────────────────────────────────────────────────────────┘

1. Message Reception (SWIFT Ingress)
   ↓
2. Message Type Detection (PACS.008 or PACS.009)
   ↓
3. Conversion to Canonical Format
   ↓
4. Account Validation
   ├─ Validates debtor account
   ├─ Validates creditor account
   └─ Identifies if both parties are Wells customers
   ↓
5. Routing Validation
   ├─ Determines optimal payment rail
   └─ Applies routing rules (see Section 1.3)
   ↓
6. Sanctions Screening
   ├─ Checks against OFAC and other sanctions lists
   └─ Flags potential compliance issues
   ↓
7. Balance Check
   ├─ Verifies sufficient funds (for outbound)
   └─ Confirms account status
   ↓
8. Payment Posting
   ├─ Debits debtor account
   └─ Credits creditor account
   ↓
9. Settlement
   ├─ Internal settlement (if both parties are Wells)
   └─ External settlement (via FED/CHIPS/SWIFT)
   ↓
10. Final Status Confirmation
```

### 1.3 Intelligent Routing Rules

The platform automatically determines the optimal payment rail based on high-level business rules:

#### Rule 1: Internal Bank Transfer (IBT)
**Condition**: Both debtor and creditor are Wells Fargo customers  
**Action**: Route via INTERNAL network  
**Benefit**: Instant settlement, no external network fees

#### Rule 2: US Domestic Routing
**Condition**: Creditor is in the US (but not both parties Wells)  
**Decision Process**:
1. **Lookup Receiving Bank Capabilities**: Check if receiving bank supports CHIPS and/or FED
2. **Apply Optimization Rules** (in priority order):
   - If customer specified FED preference → Route via FED (immediate settlement)
   - If CHIPS cutoff passed AND payment is urgent → Route via FED
   - If CHIPS queue depth is high → Route via FED
   - Otherwise → Route via CHIPS (cost-optimized)
3. **Fallback**: If only one network available, use that network

#### Rule 3: Cross-Border Routing
**Condition**: Creditor is outside the US  
**Action**: Route via SWIFT network  
**Benefit**: Global reach, standardized messaging

### 1.4 Status Confirmations (PACS.002)

The platform provides real-time status updates to the instructing bank via PACS.002 (Payment Status Report) messages at each critical processing stage:

| Status Code | Meaning | When Sent |
|------------|---------|-----------|
| **RCVD** | Received | Immediately upon message receipt |
| **ACCP** | Accepted | After successful account validation |
| **ACSP** | Accepted - Settlement in Process | During settlement phase |
| **ACSC** | Accepted - Settlement Completed | Upon successful settlement |
| **RJCT** | Rejected | When payment fails validation or compliance checks |
| **CANC** | Cancelled | When payment is cancelled before settlement |

**Key Features**:
- **Automatic Notifications**: Status updates are sent automatically without manual intervention
- **End-to-End Traceability**: Each PACS.002 references the original payment's EndToEndId and MessageId
- **Real-Time Delivery**: Status updates are published immediately to the notification topic
- **Comprehensive Information**: Includes reason codes and additional information for rejected payments

**Example Flow**:
```
Payment Received → PACS.002 (RCVD) sent
     ↓
Account Validated → PACS.002 (ACCP) sent
     ↓
Routing Determined → PACS.002 (ACSP) sent
     ↓
Settlement Completed → PACS.002 (ACSC) sent
```

---

## 2. FED Network Processing

### 2.1 Overview

The Federal Reserve Wire Network (FED) is the primary US domestic high-value payment system. The platform supports both inbound and outbound FED payment processing, handling customer-initiated and bank-to-bank transfers within the United States.

### 2.2 FED Inbound Payment Processing

#### Payment Reception

The platform accepts two types of ISO 20022 payment messages from the FED network:

##### PACS.008 (Customer Credit Transfer)
- **Source**: Wells Fargo customers initiating payments via API, online portal, or branch
- **Purpose**: Customer-initiated wire transfers routed through the FED network
- **Example Scenario**: A Wells Fargo customer sends a wire transfer to a customer at Chase Bank (US domestic)

##### PACS.009 (Financial Institution Credit Transfer)
- **Source**: Bank-to-bank transfers from other US financial institutions via FED network
- **Purpose**: Interbank settlement transactions
- **Example Scenario**: Chase Bank sends a batch of customer payments to Wells Fargo via FED

#### Message Processing Pipeline

FED inbound payments follow the same comprehensive processing pipeline as SWIFT payments:

```
┌─────────────────────────────────────────────────────────────┐
│                    FED INBOUND FLOW                         │
└─────────────────────────────────────────────────────────────┘

1. Message Reception (FED Ingress)
   ↓
2. Message Type Detection (PACS.008 or PACS.009)
   ↓
3. Conversion to Canonical Format
   ↓
4. Account Validation
   ├─ Validates debtor account
   ├─ Validates creditor account
   └─ Identifies if both parties are Wells customers
   ↓
5. Routing Validation
   ├─ Determines optimal payment rail
   └─ Applies routing rules
   ↓
6. Sanctions Screening
   ├─ Checks against OFAC and other sanctions lists
   └─ Flags potential compliance issues
   ↓
7. Balance Check
   ├─ Verifies sufficient funds (for outbound)
   └─ Confirms account status
   ↓
8. Payment Posting
   ├─ Debits debtor account
   └─ Credits creditor account
   ↓
9. Settlement
   ├─ Internal settlement (if both parties are Wells)
   └─ External settlement (via FED/CHIPS/SWIFT)
   ↓
10. Final Status Confirmation
```

#### Key Characteristics of FED Inbound Processing

- **ABA Routing Numbers**: FED messages use ABA (American Bankers Association) routing numbers instead of BIC codes
- **US Domestic Focus**: Primarily handles US domestic payments
- **Immediate Settlement**: FED provides real-time gross settlement (RTGS)
- **Same Processing Pipeline**: Uses identical validation and compliance checks as SWIFT payments
- **Status Confirmations**: Sends PACS.002 status updates at each processing stage

### 2.3 FED Outbound Payment Processing

#### When Payments Are Routed to FED

Payments are automatically routed to FED egress when:

1. **Routing Decision**: The routing engine determines FED is the optimal rail (see Section 1.3, Rule 2)
2. **Customer Preference**: Customer explicitly requests FED for immediate settlement
3. **Urgency Requirements**: Payment is urgent and CHIPS cutoff has passed
4. **Network Availability**: Only FED is available for the receiving bank

#### FED Egress Process

```
Payment Ready for Egress
    ↓
Routing Engine Determines: FED
    ↓
PaymentEvent → FED Egress Service
    ↓
Conversion to PACS.009 Format
    ├─ Uses ABA routing numbers
    ├─ Formats as Financial Institution Credit Transfer
    └─ Includes all required FED-specific fields
    ↓
Publish to FED Network
    ↓
Settlement via FED RTGS
    ↓
Status Confirmation (PACS.002 ACSC)
```

#### Key Features of FED Outbound

- **PACS.009 Format**: All outbound FED payments are converted to PACS.009 (Financial Institution Credit Transfer)
- **ABA Routing**: Uses ABA routing numbers for US banks instead of BIC codes
- **Real-Time Settlement**: FED provides immediate settlement (RTGS - Real-Time Gross Settlement)
- **High Value**: Typically used for high-value, time-sensitive payments
- **Cost Consideration**: Generally more expensive than CHIPS, but provides immediate settlement

### 2.4 FED vs CHIPS Routing Decision

When both FED and CHIPS are available for a US domestic payment, the platform applies intelligent optimization rules:

| Factor | FED Selected | CHIPS Selected |
|--------|--------------|----------------|
| **Customer Preference** | Customer explicitly requests FED | No preference specified |
| **Payment Urgency** | Urgent AND CHIPS cutoff passed | Not urgent OR CHIPS cutoff not passed |
| **CHIPS Queue Depth** | CHIPS queue is high | CHIPS queue is normal |
| **Default** | - | CHIPS (cost-optimized) |

**Business Logic**:
- **FED**: Preferred for immediate settlement, urgent payments, or when customer requests
- **CHIPS**: Preferred for cost optimization when timing is not critical

### 2.5 Status Confirmations for FED Payments

FED payments receive the same comprehensive status reporting as SWIFT payments:

- **RCVD**: Payment received from FED network
- **ACCP**: Payment accepted after validation
- **ACSP**: Settlement in process via FED
- **ACSC**: Settlement completed via FED RTGS
- **RJCT**: Payment rejected (with reason codes)

All status updates are sent via PACS.002 messages, maintaining consistency across all payment networks.

### 2.6 FED Network Advantages

- **Immediate Settlement**: Real-time gross settlement (RTGS) - funds available immediately
- **High Reliability**: Federal Reserve-operated network with high uptime
- **Regulatory Compliance**: Meets all US regulatory requirements
- **Large Value**: Handles high-value payments efficiently
- **24/7 Availability**: Extended hours for time-sensitive payments

---

## 3. CHIPS Network Processing

### 3.1 Overview

The Clearing House Interbank Payments System (CHIPS) is a US-based private-sector payment system that provides real-time final settlement for high-value payments. The platform supports both inbound and outbound CHIPS payment processing, offering a cost-effective alternative to FED for US domestic payments.

### 3.2 CHIPS Inbound Payment Processing

#### Payment Reception

The platform accepts two types of ISO 20022 payment messages from the CHIPS network:

##### PACS.008 (Customer Credit Transfer)
- **Source**: Wells Fargo customers initiating payments via API, online portal, or branch
- **Purpose**: Customer-initiated wire transfers routed through the CHIPS network
- **Example Scenario**: A Wells Fargo customer sends a wire transfer to a customer at Bank of America (US domestic)

##### PACS.009 (Financial Institution Credit Transfer)
- **Source**: Bank-to-bank transfers from other US financial institutions via CHIPS network
- **Purpose**: Interbank settlement transactions
- **Example Scenario**: JPMorgan Chase sends a batch of customer payments to Wells Fargo via CHIPS

#### Message Processing Pipeline

CHIPS inbound payments follow the same comprehensive processing pipeline as SWIFT and FED payments:

```
┌─────────────────────────────────────────────────────────────┐
│                    CHIPS INBOUND FLOW                       │
└─────────────────────────────────────────────────────────────┘

1. Message Reception (CHIPS Ingress)
   ↓
2. Message Type Detection (PACS.008 or PACS.009)
   ↓
3. Conversion to Canonical Format
   ↓
4. Account Validation
   ├─ Validates debtor account
   ├─ Validates creditor account
   └─ Identifies if both parties are Wells customers
   ↓
5. Routing Validation
   ├─ Determines optimal payment rail
   └─ Applies routing rules
   ↓
6. Sanctions Screening
   ├─ Checks against OFAC and other sanctions lists
   └─ Flags potential compliance issues
   ↓
7. Balance Check
   ├─ Verifies sufficient funds (for outbound)
   └─ Confirms account status
   ↓
8. Payment Posting
   ├─ Debits debtor account
   └─ Credits creditor account
   ↓
9. Settlement
   ├─ Internal settlement (if both parties are Wells)
   └─ External settlement (via FED/CHIPS/SWIFT)
   ↓
10. Final Status Confirmation
```

#### Key Characteristics of CHIPS Inbound Processing

- **CHIPS UID**: CHIPS messages use CHIPS Universal Identifier (UID) or BIC codes for bank identification
- **US Domestic Focus**: Primarily handles US domestic payments
- **Net Settlement**: CHIPS uses net settlement with finality at end of day
- **Same Processing Pipeline**: Uses identical validation and compliance checks as SWIFT and FED payments
- **Status Confirmations**: Sends PACS.002 status updates at each processing stage

### 3.3 CHIPS Outbound Payment Processing

#### When Payments Are Routed to CHIPS

Payments are automatically routed to CHIPS egress when:

1. **Routing Decision**: The routing engine determines CHIPS is the optimal rail (see Section 1.3, Rule 2)
2. **Cost Optimization**: CHIPS is selected as the default cost-effective option when both CHIPS and FED are available
3. **Network Availability**: Only CHIPS is available for the receiving bank
4. **Non-Urgent Payments**: Payment is not urgent and CHIPS cutoff has not passed

#### CHIPS Egress Process

```
Payment Ready for Egress
    ↓
Routing Engine Determines: CHIPS
    ↓
PaymentEvent → CHIPS Egress Service
    ↓
Conversion to PACS.009 Format
    ├─ Uses CHIPS UID or BIC
    ├─ Formats as Financial Institution Credit Transfer
    └─ Includes all required CHIPS-specific fields
    ↓
Publish to CHIPS Network
    ↓
Settlement via CHIPS Net Settlement
    ↓
Status Confirmation (PACS.002 ACSC)
```

#### Key Features of CHIPS Outbound

- **PACS.009 Format**: All outbound CHIPS payments are converted to PACS.009 (Financial Institution Credit Transfer)
- **CHIPS UID/BIC**: Uses CHIPS Universal Identifier or BIC codes for US banks
- **Net Settlement**: CHIPS provides net settlement with finality at end of day
- **Cost Effective**: Generally more cost-effective than FED for non-urgent payments
- **High Volume**: Efficiently handles large volumes of payments

### 3.4 CHIPS vs FED Routing Decision

When both CHIPS and FED are available for a US domestic payment, the platform applies intelligent optimization rules:

| Factor | CHIPS Selected | FED Selected |
|--------|----------------|--------------|
| **Customer Preference** | No preference OR customer prefers CHIPS | Customer explicitly requests FED |
| **Payment Urgency** | Not urgent OR CHIPS cutoff not passed | Urgent AND CHIPS cutoff passed |
| **CHIPS Queue Depth** | CHIPS queue is normal | CHIPS queue is high |
| **Default** | CHIPS (cost-optimized) | - |

**Business Logic**:
- **CHIPS**: Preferred for cost optimization, non-urgent payments, or when timing is not critical
- **FED**: Preferred for immediate settlement, urgent payments, or when customer explicitly requests

### 3.5 Status Confirmations for CHIPS Payments

CHIPS payments receive the same comprehensive status reporting as SWIFT and FED payments:

- **RCVD**: Payment received from CHIPS network
- **ACCP**: Payment accepted after validation
- **ACSP**: Settlement in process via CHIPS
- **ACSC**: Settlement completed via CHIPS net settlement
- **RJCT**: Payment rejected (with reason codes)

All status updates are sent via PACS.002 messages, maintaining consistency across all payment networks.

### 3.6 CHIPS Network Advantages

- **Cost Effective**: Lower transaction costs compared to FED
- **High Volume**: Efficiently processes large volumes of payments
- **Net Settlement**: Net settlement reduces liquidity requirements
- **Reliability**: Private-sector network with high reliability
- **US Domestic Focus**: Optimized for US domestic payments

---

## 4. Wells-Initiated Wire Payments Flow

### 4.1 Overview

Wells Fargo customers can initiate wire payments through multiple channels (branch, online portal, API, scheduled payments). The platform accepts these payments via the Wells Ingress API, processes them through the same validation and routing pipeline, and automatically determines the optimal settlement network. Payments can be routed internally (IBT), or externally via FED, CHIPS, or SWIFT networks based on intelligent routing logic.

### 4.2 Payment Initiation Channels

Wells customers can initiate wire payments through:

#### Branch Applications
- **Source**: Branch staff initiating payments on behalf of customers
- **Format**: PACS.008 (Customer Credit Transfer)
- **Channel**: REST API with `X-Channel: BRANCH` header

#### Online Banking Portal
- **Source**: Customers initiating payments through web/mobile applications
- **Format**: PACS.008 (Customer Credit Transfer) or simplified JSON
- **Channel**: REST API with `X-Channel: ONLINE` header

#### Scheduled Payment Systems
- **Source**: Standing orders and recurring payments from other systems
- **Format**: PACS.008 (Customer Credit Transfer)
- **Channel**: REST API with `X-Channel: SCHEDULED` header

#### Direct API Integration
- **Source**: Internal applications and third-party integrations
- **Format**: PACS.008 XML or JSON
- **Channel**: REST API with `X-Channel: API` header

### 4.3 Wells Ingress API

The Wells Ingress REST API (`/api/v1/wells/payments/submit`) accepts payments in two formats:

#### PACS.008 XML Format
- **Content-Type**: `application/xml`
- **Purpose**: Full ISO 20022 PACS.008 message
- **Use Case**: Branch applications, scheduled systems, direct integrations

#### Simplified JSON Format
- **Content-Type**: `application/json`
- **Purpose**: Simplified payment request for online portals
- **Use Case**: Customer-facing applications requiring easy integration

**Key Features**:
- **Channel Tracking**: `X-Channel` header identifies payment source
- **Immediate Acknowledgment**: Returns 202 Accepted with payment details
- **Kafka Integration**: Publishes to `ingress.wells.in` topic for processing
- **Idempotency**: Payments identified by `endToEndId` for duplicate prevention

### 4.4 Payment Processing Flow

Wells-initiated payments follow the complete processing pipeline:

```
┌─────────────────────────────────────────────────────────────┐
│              WELLS-INITIATED PAYMENT FLOW                   │
└─────────────────────────────────────────────────────────────┘

1. Customer Initiates Payment
   ├─ Branch, Online Portal, API, or Scheduled System
   └─ Submits PACS.008 via Wells Ingress API
   ↓
2. Wells Ingress API
   ├─ Validates request
   ├─ Converts JSON to PACS.008 (if needed)
   └─ Publishes to ingress.wells.in topic
   ↓
3. Wells Ingress Service
   ├─ Consumes from ingress.wells.in
   ├─ Parses PACS.008 message
   └─ Converts to canonical PaymentEvent
   ↓
4. Payment Orchestrator
   ├─ Receives PaymentEvent
   └─ Routes to processing pipeline
   ↓
5. Account Validation
   ├─ Validates debtor account (Wells customer)
   ├─ Validates creditor account
   └─ Identifies if both parties are Wells customers
   ↓
6. Routing Validation
   ├─ Applies intelligent routing rules
   └─ Determines optimal payment rail:
      ├─ IBT (if both parties are Wells)
      ├─ FED (if US domestic, urgent, or customer preference)
      ├─ CHIPS (if US domestic, cost-optimized)
      └─ SWIFT (if cross-border)
   ↓
7. Sanctions Screening
   ├─ Checks against OFAC and other sanctions lists
   └─ Flags potential compliance issues
   ↓
8. Balance Check
   ├─ Verifies sufficient funds
   └─ Confirms account status
   ↓
9. Payment Posting
   ├─ Debits debtor account
   └─ Credits creditor account (or settlement account)
   ↓
10. Settlement
    ├─ IBT: Internal settlement (both parties Wells)
    ├─ FED: Settlement via FED RTGS
    ├─ CHIPS: Settlement via CHIPS net settlement
    └─ SWIFT: Settlement via SWIFT network
    ↓
11. Egress (if external)
    ├─ Converts to PACS.009 (for FED/CHIPS/SWIFT)
    └─ Publishes to respective egress service
    ↓
12. Final Status Confirmation
    └─ PACS.002 (ACSC) sent to customer
```

### 4.5 Routing Logic for Wells-Initiated Payments

The platform automatically determines the optimal payment rail based on account validation and payment characteristics:

#### Scenario 1: Internal Book Transfer (IBT)
**Condition**: Both debtor and creditor are Wells Fargo customers  
**Action**: Route via INTERNAL network (IBT)  
**Settlement**: Instant internal settlement, no external network fees  
**Message**: No egress message required (internal only)

**Example**: Wells customer A sends money to Wells customer B

#### Scenario 2: US Domestic - FED Routing
**Condition**: Creditor is in the US, FED selected based on:
- Customer explicitly requests FED
- Payment is urgent AND CHIPS cutoff has passed
- CHIPS queue depth is high
- Only FED is available for receiving bank

**Action**: Route via FED network  
**Egress**: Convert to PACS.009, publish to FED egress  
**Settlement**: Real-time gross settlement (RTGS)

**Example**: Wells customer sends urgent payment to Chase Bank (US)

#### Scenario 3: US Domestic - CHIPS Routing
**Condition**: Creditor is in the US, CHIPS selected based on:
- No customer preference for FED
- Payment is not urgent OR CHIPS cutoff not passed
- CHIPS queue depth is normal
- Both CHIPS and FED available (default to CHIPS for cost optimization)

**Action**: Route via CHIPS network  
**Egress**: Convert to PACS.009, publish to CHIPS egress  
**Settlement**: Net settlement with finality at end of day

**Example**: Wells customer sends non-urgent payment to Bank of America (US)

#### Scenario 4: Cross-Border - SWIFT Routing
**Condition**: Creditor is outside the US  
**Action**: Route via SWIFT network  
**Egress**: Convert to PACS.009, publish to SWIFT egress  
**Settlement**: SWIFT network settlement

**Example**: Wells customer sends payment to HDFC Bank (India)

### 4.6 Wells-Initiated Cancellation Requests

Wells customers can request cancellation of payments they initiated:

#### camt.055 (Customer Payment Cancellation Request)
- **Sender**: Wells Fargo customer
- **Receiver**: Wells Fargo (via Wells Ingress API)
- **Purpose**: Customer requests cancellation of a payment they initiated
- **Processing**: Wells Fargo converts to camt.056 before forwarding to receiving bank (if payment already sent externally)

**Flow**:
```
Wells Customer → camt.055 → Wells Ingress API
                              ↓
                    CancellationHandler
                              ↓
                    ┌─────────┴─────────┐
                    │                   │
            Payment Not Settled    Payment Settled
                    │                   │
            Direct Cancellation    Two-Layer Process
            (CANC status)         (camt.029 → pacs.004)
```

### 4.7 Wells-Initiated Outbound Messages

Wells Fargo can also create and send payment messages to other banks:

#### PACS.009 (Financial Institution Credit Transfer)
- **Source**: Wells Fargo (bank-initiated)
- **Purpose**: Bank-to-bank transfers to external institutions
- **Egress**: Sent via FED, CHIPS, or SWIFT egress services
- **Use Case**: Corporate payments, batch processing, interbank settlements

#### camt.056 (FI-to-FI Payment Cancellation Request)
- **Source**: Wells Fargo (on behalf of customer)
- **Purpose**: Request cancellation from receiving bank
- **Egress**: Sent via same network as original payment (FED/CHIPS/SWIFT)
- **Use Case**: Customer-initiated cancellation of external payment

### 4.8 Status Confirmations for Wells-Initiated Payments

Wells-initiated payments receive comprehensive status reporting:

- **RCVD**: Payment received via Wells Ingress API
- **ACCP**: Payment accepted after validation
- **ACSP**: Settlement in process (IBT/FED/CHIPS/SWIFT)
- **ACSC**: Settlement completed
- **RJCT**: Payment rejected (with reason codes)
- **CANC**: Payment cancelled (if cancelled before settlement)

All status updates are sent via PACS.002 messages, providing real-time visibility to customers and internal systems.

### 4.9 Key Advantages of Wells-Initiated Payments

- **Unified API**: Single API endpoint for all payment channels
- **Flexible Input**: Supports both XML (PACS.008) and JSON formats
- **Intelligent Routing**: Automatically selects optimal payment rail
- **Cost Optimization**: Defaults to most cost-effective network (CHIPS for US domestic)
- **Real-Time Processing**: End-to-end processing in seconds
- **Complete Traceability**: All payments tracked via EndToEndId
- **Multi-Channel Support**: Branch, online, API, and scheduled payments

---

## 5. Post-Settlement Fund Return Process

### 5.1 Overview

When a payment has already been settled (funds credited to the recipient), the platform supports a sophisticated two-layer process for handling cancellation requests:

1. **Investigation Layer** ("The Talk"): Determines if a refund is possible
2. **Settlement Layer** ("The Action"): Actually returns the funds if approved

This process ensures that funds are not moved without proper investigation and approval, maintaining legal and regulatory compliance.

### 5.2 Cancellation Request Flow

#### camt.055 (Customer Payment Cancellation Request)
- **Sender**: Customer (debtor)
- **Receiver**: Customer's bank (debtor bank)
- **Purpose**: Customer requests cancellation of a payment
- **Note**: The debtor bank converts this to camt.056 before forwarding to Wells Fargo

#### camt.056 (FI-to-FI Payment Cancellation Request)
- **Sender**: Instructing bank (debtor bank)
- **Receiver**: Wells Fargo (creditor bank)
- **Purpose**: Bank requests cancellation from the receiving bank
- **Universal Support**: Can be received via any ingress channel (SWIFT, FED, CHIPS, or Wells API)

**Process**:
```
Customer → camt.055 → Debtor Bank
                           ↓
                    camt.056 → Wells Fargo
                           ↓
                  CancellationHandler
```

### 5.3 Investigation Layer - Resolution of Investigation (camt.029)

Upon receiving a camt.056 cancellation request, the platform creates a cancellation case and investigates whether a refund is possible. The investigation result is communicated via camt.029 (Resolution of Investigation).

#### Investigation Process

1. **Case Creation**: A unique case ID is generated for tracking
2. **Payment Status Check**: Determines if payment is settled or pending
3. **Business Rule Evaluation**: Applies business rules to determine refund eligibility
4. **Resolution Decision**: One of three outcomes:

#### Resolution Status Codes

| Status Code | Meaning | Scenario |
|------------|---------|----------|
| **RJCT** | Refused | Refund cannot be processed (e.g., goods already shipped, payment already applied, duplicate request) |
| **PDCR** | Pending Cancellation Request | Requires customer/merchant approval - investigation ongoing |
| **ACCP** | Accepted | Refund approved - will proceed to settlement layer |

**Example Scenarios**:

- **RJCT (Refused)**: 
  - Payment already applied to customer account
  - Goods/services already delivered
  - Duplicate cancellation request
  - Fraud verification completed (payment is legitimate)

- **PDCR (Pending)**:
  - Waiting for merchant approval
  - Customer dispute requires investigation
  - Regulatory review in progress

- **ACCP (Approved)**:
  - Customer request validated
  - Merchant agrees to refund
  - All business rules satisfied

### 5.4 Settlement Layer - Payment Return (pacs.004)

If the investigation results in an **ACCP** (Approved) status, the platform proceeds to the settlement layer and generates a pacs.004 (Payment Return) message to actually return the funds.

#### Key Features of pacs.004

- **Value Message**: This is not just a notification - it actually moves funds
- **Fee Handling**: Return amount may be less than original if processing fees are deducted
  - Formula: `Return Amount = Original Amount - Processing Fees`
- **Settlement Date**: Includes its own settlement date for the return transaction
- **UETR Linkage**: Maintains traceability to original payment via Unique End-to-End Transaction Reference

#### Complete Fund Return Flow

```
camt.056 Received
    ↓
Case Created (Status: PENDING)
    ↓
Investigation Started
    ↓
Business Rules Evaluated
    ↓
┌─────────────────────────────────────┐
│  INVESTIGATION LAYER                 │
│  camt.029 Generated                 │
└───────────┬─────────────────────────┘
            │
    ┌───────┴───────┐
    │               │               │
  RJCT            PDCR            ACCP
    │               │               │
Refused      Pending Review    Approved
    │               │               │
    │               │               ↓
    │               │      ┌────────────────────┐
    │               │      │ SETTLEMENT LAYER   │
    │               │      │ pacs.004 Generated │
    │               │      └─────────┬──────────┘
    │               │                 │
    │               │            Funds Returned
    │               │            (Debit creditor,
    │               │             Credit debtor)
    │               │                 │
    └───────────────┴─────────────────┘
              Case Closed
```

### 5.5 Case Management

Each cancellation request is tracked through a comprehensive case management system:

- **Case ID**: Unique identifier for tracking
- **Status Tracking**: PENDING → PDCR/REFUSED/APPROVED → RETURNED
- **Message Linking**: All messages (camt.056, camt.029, pacs.004) linked via UETR/EndToEndId
- **Audit Trail**: Complete history of investigation and settlement activities
- **Multi-Channel Support**: Cases can originate from any ingress channel

---

## 6. Platform Capabilities Summary

### 6.1 Payment Processing Capabilities

- ✅ **Multi-Format Support**: Handles PACS.008 and PACS.009 messages
- ✅ **Universal Ingress**: Accepts payments from SWIFT, FED, CHIPS, and Wells channels
- ✅ **Intelligent Routing**: Automatically selects optimal payment rail
- ✅ **Real-Time Processing**: End-to-end processing in seconds
- ✅ **High Volume**: Designed for enterprise-scale transaction volumes

### 6.2 Status Reporting Capabilities

- ✅ **Automatic Confirmations**: PACS.002 sent at every processing stage
- ✅ **Real-Time Updates**: Immediate notification to instructing banks
- ✅ **Comprehensive Status Codes**: RCVD, ACCP, ACSP, ACSC, RJCT, CANC
- ✅ **End-to-End Traceability**: All messages linked via UETR/EndToEndId
- ✅ **Reason Code Support**: Detailed rejection reasons for troubleshooting

### 6.3 Fund Return Capabilities

- ✅ **Two-Layer Process**: Investigation followed by settlement
- ✅ **Universal Cancellation Support**: Accepts camt.056 from any ingress channel
- ✅ **Business Rule Engine**: Automated decision-making for refund eligibility
- ✅ **Fee Handling**: Supports processing fees in return transactions
- ✅ **Case Management**: Complete tracking and audit trail

### 6.4 Operational Capabilities

- ✅ **Error Management UI**: Operator-friendly dashboard for monitoring and resolution
- ✅ **Real-Time Monitoring**: Live status of all payments in the system
- ✅ **Exception Handling**: Automated retry and manual intervention support
- ✅ **Compliance Screening**: Integrated sanctions and regulatory checks

---


## Appendix: Future Enhancements

The following capabilities are planned for future releases:

- **Additional Egress Enhancements**: Enhanced egress processing capabilities
- **Egress Capabilities**: Additional egress processing enhancements
- **Wells Customer-Triggered Returns**: Customer-initiated fund return requests
- **Notification and Admin Messages**: Handling of additional ISO 20022 message types
- **Non-Functional Requirements**: Performance, scalability, and reliability metrics

---

**Document End**

