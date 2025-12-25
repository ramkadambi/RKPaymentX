# Customer-Initiated Wire Transfer Routing Logic

## Overview

When Wells customers initiate a wire transfer via API, online portal, or branch, they do **not** specify the routing mechanism (IBT, Fed, Chips, or SWIFT). The payment engine automatically determines the optimal routing based on account validation and payment characteristics.

## Routing Flow

```
Customer Initiates Wire Transfer (PACS.008)
         │
         ▼
┌────────────────────────────────────┐
│  Account Validation                │
│  (Check both parties)              │
└────────────┬───────────────────────┘
             │
             ▼
    ┌────────────────┐
    │ Both parties   │
    │ are Wells?     │
    └───┬────────┬───┘
        │        │
    YES │        │ NO
        │        │
        ▼        ▼
    ┌──────┐  ┌──────────────────────┐
    │ IBT  │  │ Check Creditor       │
    │      │  │ Country              │
    └──────┘  └───┬──────────────────┘
                  │
        ┌─────────┴─────────┐
        │                   │
    ┌───▼────┐        ┌─────▼─────┐
    │ US     │        │ Outside   │
    │        │        │ US        │
    └───┬────┘        └─────┬─────┘
        │                   │
        │                   ▼
        │            ┌──────────────┐
        │            │  SWIFT OUT   │
        │            └──────────────┘
        │
        ▼
┌────────────────────────────────────┐
│ Check Receiving Bank Capabilities  │
│ (CHIPS and/or FED)                 │
└───┬────────────────────────────────┘
    │
    ├─── Both CHIPS and FED available
    │    │
    │    ├─── Customer specified FED? ──► FED (immediate settlement)
    │    │
    │    ├─── CHIPS cutoff passed AND payment urgent? ──► FED
    │    │
    │    └─── Otherwise ──► CHIPS (cheaper option)
    │
    ├─── Only FED available ──► FED
    │
    └─── Only CHIPS available ──► CHIPS
```

## Detailed Routing Rules

### 1. IBT (Internal Bank Transfer)

**Condition**: Both debit party AND credit party are Wells customers

**Rule**: `WF-PACS008-R1-INTERNAL-CREDIT`
- Priority: 1 (highest - checked first)
- Conditions:
  - `pacs.008.debtor_agent_bic == "WFBIUS6SXXX"`
  - `pacs.008.creditor_agent_bic == "WFBIUS6SXXX"`
- Action: Route via `INTERNAL` network (IBT)

**Account Validation**: The account validation service checks if both parties are Wells customers and enriches the payment event accordingly.

### 2. US Routing (Fed/Chips)

**Condition**: Credit party is in US (but not both parties Wells)

**Rules Applied in Priority Order**:

#### 2.1. Lookup Receiving Bank Capabilities (R2)

**Rule**: `WF-PACS009-R2-US-INTERMEDIARY-LOOKUP` (or equivalent for PACS.008)
- Priority: 2
- Conditions:
  - `pacs.008.debtor_agent_bic == "WFBIUS6SXXX"` (or debtor is Wells)
  - `ultimate_creditor_country == "US"`
- Action: Invoke services to lookup:
  - BIC to ABA lookup
  - BIC to CHIPS lookup

This populates `next_bank` context with:
- `chips_id_exists`: boolean
- `aba_exists`: boolean

#### 2.2. Optimization Rule - Both CHIPS and FED Available (R5)

**Rule**: `WF-PACS008-R5-CHIPS-OR-FED-OPTIMIZATION` (needs to be created for PACS.008)
- Priority: 5 (evaluated when both networks are available)
- Conditions:
  - `next_bank.chips_id_exists == true`
  - `next_bank.aba_exists == true`
- Decision Matrix (evaluated in order):
  1. **Customer preference == FED** → Route via FED (for immediate settlement)
  2. **CHIPS cutoff passed AND payment urgency == HIGH** → Route via FED
  3. **Otherwise** → Route via CHIPS (cheaper option)

#### 2.3. CHIPS Only Available (R3)

**Rule**: `WF-PACS008-R3-CHIPS-DEFAULT`
- Priority: 3
- Conditions:
  - `next_bank.chips_id_exists == true`
  - `next_bank.aba_exists == false`
- Action: Route via `CHIPS`

#### 2.4. FED Only Available (R4)

**Rule**: `WF-PACS008-R4-FED-ONLY`
- Priority: 4
- Conditions:
  - `next_bank.chips_id_exists == false`
  - `next_bank.aba_exists == true`
- Action: Route via `FED`

### 3. Outside US Routing

**Condition**: Credit party is outside US

**Rule**: `WF-PACS008-R6-CROSSBORDER-OUT-SWIFT`
- Priority: 10
- Conditions:
  - `ultimate_creditor_country_not == "US"`
- Action: Route via `SWIFT`

## Key Implementation Points

### Account Validation

The account validation service should:
1. Check if debtor account is a Wells customer account
2. Check if creditor account is a Wells customer account
3. Enrich `enrichmentContext.accountValidation` with:
   - `vostro_with_us`: boolean (if creditor bank has vostro account)
   - `nostro_accounts_available`: boolean
   - Other relevant account validation data

### Routing Context

The routing validation service should:
1. Read account validation enrichment
2. Determine if both parties are Wells (for IBT routing)
3. Lookup receiving bank capabilities (CHIPS/FED)
4. Check customer preference (from `routingContext.customerPreference`)
5. Check payment urgency (from `routingContext.paymentUrgency`)
6. Check payment ecosystem (CHIPS cutoff status, queue depth)

### Customer Preference

When customers optionally specify FED for immediate settlement:
- This should be set in `PaymentEvent.routingContext.customerPreference = RoutingNetwork.FED`
- The routing engine checks this first in the optimization rule (R5)

### Payment Urgency

Payment urgency is determined from:
- Customer input (if specified)
- Payment characteristics (amount, time-sensitive flags)
- Set in `PaymentEvent.routingContext.paymentUrgency`

### CHIPS Cutoff Logic

When both CHIPS and FED are available:
- If CHIPS cutoff has passed AND payment is urgent → Route via FED
- Otherwise → Route via CHIPS (cheaper, preferred when cutoff hasn't passed)

## Current Implementation Status

### ✅ Implemented

1. IBT routing when both parties are Wells (R1) - ✅ Fixed
2. SWIFT routing for outside US (R6) - ✅
3. CHIPS only routing (R3) - ✅
4. FED only routing (R4) - ✅
5. Optimization rule for both CHIPS and FED (R5) - ✅ (but needs adjustment)

### ✅ Implemented

1. **R5 Decision Matrix Order**: ✅ Updated to check:
   - customer_preference == FED (first priority)
   - chips_cutoff_passed == true AND payment_urgency == HIGH (combined condition)
   - chips_queue_depth == HIGH
   - Otherwise → CHIPS

2. **PACS.008 US Routing Rules**: ✅ Added equivalent rules (R2-R5) for PACS.008 customer-initiated payments

3. **Account Validation Integration**: ✅ IBT routing rule checks both debtor and creditor agents are Wells Fargo

## Recommendations

1. Update R5 decision matrix to check customer_preference first
2. Combine CHIPS cutoff and urgency check into a single condition
3. Add PACS.008 equivalent rules for US routing (R2-R5)
4. Verify account validation service properly detects Wells customers for both parties

