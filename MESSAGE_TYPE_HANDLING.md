# Message Type Handling - PACS.008 vs PACS.009

## Overview

This document explains how the payment engine handles PACS.008 (Customer Credit Transfer) and PACS.009 (Financial Institution Credit Transfer) messages according to business requirements.

## Business Requirements

1. **Ingress**: Accept both PACS.008 and PACS.009 from various sources
2. **Storage**: All payments stored internally as PACS.008-like canonical format (customer-centric)
3. **Egress**: Convert to PACS.009 when sending to external networks (Fed, Chips, SWIFT)

## Ingress Handling

### Ingress Services Accept Both Message Types

All ingress services are designed to handle both PACS.008 and PACS.009 messages:

| Service | PACS.008 Sources | PACS.009 Sources |
|---------|------------------|------------------|
| **SwiftIngressService** | Customer-initiated payments from external banks | Bank-to-bank transfers from external banks |
| **FedIngressService** | Wells customers via API/portal/branch (routed via Fed) | Bank-to-bank transfers via Fed network |
| **ChipsIngressService** | Wells customers via API/portal/branch (routed via Chips) | Bank-to-bank transfers via Chips network |
| **WellsIngressService** | Wells customers via API/portal/branch (Wells-originated payments) | N/A (Wells-originated only) |

### Message Type Detection

Ingress services automatically detect the message type by examining the incoming message:
- **PACS.008**: Contains `CdtTrfTxInf` (Customer Credit Transfer Transaction Information)
- **PACS.009**: Contains `FICdtTrf` (Financial Institution Credit Transfer)

The detected message type is preserved in `PaymentEvent.sourceMessageType` for audit purposes.

### Conversion to Canonical Model

All incoming messages (whether PACS.008 or PACS.009) are converted to the canonical `PaymentEvent` model, which is:
- **Customer-centric** (PACS.008-like structure)
- **Network-agnostic** (no rail-specific fields)
- Contains:
  - `debtorAgent` / `creditorAgent` (financial institutions)
  - `debtor` / `creditor` (actual customers/parties)
  - `amount`, `currency`, `endToEndId`, etc.

This ensures consistent storage and processing regardless of the original message format.

## Storage

All payments are stored in the payment ecosystem using the canonical `PaymentEvent` model, which is essentially a PACS.008-like, customer-centric format. This provides:

1. **Consistency**: All payments look the same internally
2. **Customer-centric view**: Focus on actual debtor/creditor parties
3. **Simplified processing**: No need to handle different internal formats

## Egress Handling

### Conversion to PACS.009

When payments need to be sent to external networks, they are converted from the internal PACS.008-like format to PACS.009:

| Egress Service | Output Format | Usage |
|----------------|---------------|-------|
| **FedEgressService** | PACS.009 | Sends to Fed network (bank-to-bank) |
| **ChipsEgressService** | PACS.009 | Sends to Chips network (bank-to-bank) |
| **SwiftEgressService** | PACS.009 (or PACS.008 for customer payments) | Sends to SWIFT network |
| **IbtEgressService** | Internal settlement (no message) | Internal transfers only |

### Egress Conversion Logic

Egress services convert the canonical `PaymentEvent` to PACS.009 format:
- Uses `FICdtTrf` (Financial Institution Credit Transfer) structure
- Focuses on interbank settlement
- May omit customer party details (depending on network requirements)
- Uses appropriate identifiers (BIC for SWIFT, ABA for Fed, CHIPS UID for Chips)

## Flow Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                     INGRESS LAYER                           │
│                                                             │
│  PACS.008 (customer-initiated) ──┐                        │
│  PACS.009 (bank-to-bank) ────────┼──► Ingress Services    │
│                                   │    (detect type)        │
└───────────────────────────────────┼─────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────┐
│              CANONICAL PAYMENT EVENT                        │
│         (PACS.008-like, customer-centric)                   │
│                                                             │
│  - debtorAgent / creditorAgent                              │
│  - debtor / creditor                                        │
│  - amount, currency, endToEndId                             │
│  - sourceMessageType (preserved for audit)                  │
└───────────────────────────────────┬─────────────────────────┘
                                    │
                                    │ Storage
                                    │ Processing
                                    │ Enrichment
                                    │ Routing
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────┐
│                     EGRESS LAYER                            │
│                                                             │
│  PaymentEvent ───────────────► Egress Services             │
│                                    │                         │
│                                    ▼                         │
│                          Convert to PACS.009                │
│                                    │                         │
│  ┌────────────────┐  ┌────────────────┐  ┌──────────────┐ │
│  │ FedEgress      │  │ ChipsEgress    │  │ SwiftEgress  │ │
│  │ PACS.009       │  │ PACS.009       │  │ PACS.009     │ │
│  └────────────────┘  └────────────────┘  └──────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

## Key Implementation Details

### Ingress Services

**FedIngressService** and **ChipsIngressService**:
- Detect message type (PACS.008 vs PACS.009) from incoming message
- Parse accordingly
- Convert to canonical `PaymentEvent` (always customer-centric)
- Set `sourceMessageType` to reflect original message type

**Example detection logic** (mock implementation):
```java
boolean isPacs009 = rawMessage.contains("FICdtTrf") || rawMessage.contains("pacs.009");
MessageSource detectedMessageType = isPacs009 
    ? MessageSource.ISO20022_PACS009
    : MessageSource.ISO20022_PACS008;
```

### Egress Services

**FedEgressService**, **ChipsEgressService**, **SwiftEgressService**:
- Read canonical `PaymentEvent` from final status topic
- Convert to PACS.009 format (interbank transfer)
- Generate appropriate network-specific identifiers (ABA, CHIPS UID, BIC)
- Send to external network

## Benefits

1. **Flexibility**: Accepts both customer-initiated and bank-to-bank messages
2. **Consistency**: Unified internal representation (PACS.008-like)
3. **Auditability**: Original message type preserved in `sourceMessageType`
4. **Compliance**: Proper conversion to PACS.009 for interbank networks
5. **Customer-centric**: Internal view always focuses on actual customers/parties

## Notes

- The canonical model is "PACS.008-like" rather than strict PACS.008 to allow for network-agnostic processing
- Original message format is preserved in `sourceMessageRaw` for audit purposes
- Egress services may generate PACS.008 for certain customer payments via SWIFT, depending on routing rules

