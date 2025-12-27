# Fee Calculation Process Guide

## Overview

This document explains how fees are calculated and applied in the payment processing system. It is designed for Business Analysts and Testers to understand the fee logic, fee schedules, and how fees are applied based on different scenarios.

## Table of Contents

1. [Fee Schedule Structure](#fee-schedule-structure)
2. [Fee Types](#fee-types)
3. [Charge Bearer Codes](#charge-bearer-codes)
4. [Fee Lookup Logic](#fee-lookup-logic)
5. [Fee Application Rules](#fee-application-rules)
6. [Scenarios with Examples](#scenarios-with-examples)
7. [Testing Scenarios](#testing-scenarios)

---

## Fee Schedule Structure

The system maintains two types of fee schedules:

### 1. Default Fees (Standard Fees)

Default fees are applied to all banks when no negotiated fee exists. These are the standard rates for processing payments.

**Location**: `payment-satellites/src/main/resources/data/fee_schedules.json`

**Default Fee Schedule**:
```json
{
  "default_fees": {
    "inbound_swift": "$25.00",      // Processing inbound SWIFT payment
    "inbound_fed": "$15.00",         // Processing inbound FED payment
    "inbound_chips": "$10.00",       // Processing inbound CHIPS payment
    "outbound_swift": "$30.00",     // Processing outbound SWIFT payment
    "outbound_fed": "$20.00",        // Processing outbound FED payment
    "outbound_chips": "$15.00",      // Processing outbound CHIPS payment
    "correspondent_processing": "$10.00"  // Fee for correspondent bank processing
  }
}
```

### 2. Negotiated Fees (Relationship-Specific Fees)

Negotiated fees are special rates agreed upon with specific banks. These override default fees when applicable.

**Current Negotiated Fee Schedules**:

| Bank | BIC | Fee Type | Negotiated Fee | Default Fee | Savings |
|------|-----|----------|----------------|-------------|---------|
| HDFC Bank | HDFCINBB | inbound_swift | $20.00 | $25.00 | $5.00 |
| HDFC Bank | HDFCINBB | outbound_swift | $25.00 | $30.00 | $5.00 |
| State Bank of India | SBININBB | inbound_swift | $18.00 | $25.00 | $7.00 |
| State Bank of India | SBININBB | correspondent_processing | $8.00 | $10.00 | $2.00 |
| JPMorgan Chase | CHASUS33 | inbound_chips | $7.00 | $10.00 | $3.00 |
| JPMorgan Chase | CHASUS33 | outbound_chips | $12.00 | $15.00 | $3.00 |
| Bank of America | BOFAUS3N | inbound_fed | $12.00 | $15.00 | $3.00 |
| Bank of America | BOFAUS3N | outbound_fed | $18.00 | $20.00 | $2.00 |

---

## Fee Types

Fees are categorized based on:
1. **Payment Direction**: Inbound (coming into Wells Fargo) or Outbound (going out from Wells Fargo)
2. **Payment Network**: SWIFT, FED, or CHIPS
3. **Processing Type**: Regular processing or Correspondent processing

**Fee Type Format**: `{direction}_{network}`

Examples:
- `inbound_swift` - Inbound SWIFT payment
- `outbound_fed` - Outbound FED payment
- `correspondent_processing` - Fee for correspondent bank processing

---

## Charge Bearer Codes

Charge bearer codes determine **who pays the fees** and **how fees are applied**:

### OUR / DEBT - Sender (Debtor) Pays All Fees

- **Who Pays**: The sender (debtor) pays all fees
- **Fee Deduction**: Fees are **NOT deducted** from the principal amount
- **Fee Settlement**: Fees are **billed separately** to the sender
- **Principal Amount**: Remains unchanged (full amount reaches beneficiary)

**Example**:
- Payment Amount: $10,000.00
- Fee: $25.00
- **Beneficiary Receives**: $10,000.00 (full amount)
- **Sender is Billed**: $25.00 separately

### SHA / SHAR - Shared Fees

- **Who Pays**: Fees are shared between sender and beneficiary
- **Fee Deduction**: Fees are **deducted** from the principal amount
- **Fee Settlement**: Fees are **deducted** before payment reaches beneficiary
- **Principal Amount**: Reduced by the fee amount

**Example**:
- Payment Amount: $10,000.00
- Fee: $25.00
- **Beneficiary Receives**: $9,975.00 (amount minus fee)
- **Fee is Deducted**: From the payment amount

### CRED - Receiver (Creditor) Pays All Fees

- **Who Pays**: The receiver (creditor) pays all fees
- **Fee Deduction**: Fees are **deducted** from the principal amount
- **Fee Settlement**: Fees are **deducted** before payment reaches beneficiary
- **Principal Amount**: Reduced by the fee amount

**Example**:
- Payment Amount: $10,000.00
- Fee: $25.00
- **Beneficiary Receives**: $9,975.00 (amount minus fee)
- **Fee is Deducted**: From the payment amount

---

## Fee Lookup Logic

The system follows this priority order when determining which fee to apply:

```
1. Check if negotiated fee exists for the bank
   ├─ YES → Use negotiated fee
   └─ NO → Continue to step 2

2. Use default fee for the fee type
```

**Key Points**:
- Negotiated fees **override** default fees
- If a bank has a negotiated fee for a specific fee type, that fee is used
- If a bank doesn't have a negotiated fee for a specific fee type, default fee is used
- Negotiated fees are bank-specific and fee-type-specific

**Example**:
- JPMC has negotiated fee for `outbound_chips` ($12.00)
- JPMC does **NOT** have negotiated fee for `outbound_fed`
- Payment to JPMC via CHIPS → Uses negotiated fee ($12.00)
- Payment to JPMC via FED → Uses default fee ($20.00)

---

## PACS.008 Amount Fields

### InstdAmt (Instructed Amount)
- **Definition**: Original amount sent by the customer
- **Behavior**: **NEVER changes** - always preserved as the original amount
- **Purpose**: Represents what the customer instructed to pay
- **Example**: Customer sends $10,000.00 → InstdAmt = $10,000.00 (always)

### IntrBkSttlmAmt (Interbank Settlement Amount)
- **Definition**: Amount that will actually be settled between banks
- **Behavior**: **Changes based on fee deductions**
  - If fees are deducted (SHA/SHAR/CRED): IntrBkSttlmAmt = InstdAmt - Fee
  - If fees are billed separately (OUR/DEBT): IntrBkSttlmAmt = InstdAmt (unchanged)
- **Purpose**: Represents the net amount after fee deductions
- **Example**: 
  - Customer sends $10,000.00, Fee $20.00 (SHA)
  - InstdAmt = $10,000.00 (preserved)
  - IntrBkSttlmAmt = $9,980.00 (after fee deduction)

### ChrgsInf (Charges Information)
- **Definition**: Block containing fee details when fees are deducted
- **Structure**:
  ```xml
  <ChrgsInf>
      <Amt Ccy="USD">20.00</Amt>  <!-- Fee amount -->
      <Agt>
          <FinInstnId>
              <BIC>WFBIUS6SXXX</BIC>  <!-- Bank that took the fee -->
          </FinInstnId>
      </Agt>
  </ChrgsInf>
  ```
- **When Populated**: Only when fees are deducted from principal (SHA/SHAR/CRED)
- **When NOT Populated**: When fees are billed separately (OUR/DEBT)

## Fee Application Rules

### Rule 1: Fee Amount Determination

1. Identify the fee type based on:
   - Payment direction (inbound/outbound)
   - Payment network (SWIFT/FED/CHIPS)
   - Whether correspondent processing is needed

2. Identify the bank BIC:
   - **Inbound**: Use debtor agent BIC (sending bank)
   - **Outbound**: Use creditor agent BIC (receiving bank)
   - **Correspondent**: Use creditor agent BIC (bank needing correspondent)

3. Lookup fee:
   - Check negotiated fees first
   - Fall back to default fees if no negotiated fee exists

### Rule 2: Fee Deduction Logic

Based on charge bearer code:

| Charge Bearer | Deduct from Principal? | Fee Settlement Method |
|---------------|------------------------|----------------------|
| OUR / DEBT | ❌ NO | BILLING (billed separately) |
| SHA / SHAR | ✅ YES | DEDUCTED (from principal) |
| CRED | ✅ YES | DEDUCTED (from principal) |

### Rule 3: Amount Field Assignment

```
InstdAmt (Instructed Amount):
    = Original Amount (ALWAYS preserved, never changes)

IntrBkSttlmAmt (Interbank Settlement Amount):
    If deductFromPrincipal = TRUE (SHA/SHAR/CRED):
        = Original Amount - Fee Amount
    If deductFromPrincipal = FALSE (OUR/DEBT):
        = Original Amount (unchanged, fees billed separately)
```

### Rule 4: PACS.008 Message Structure

When generating PACS.008 messages:

```xml
<CdtTrfTxInf>
    <Amt>
        <InstdAmt Ccy="USD">10000.00</InstdAmt>  <!-- Original amount - NEVER changes -->
    </Amt>
    <IntrBkSttlmAmt Ccy="USD">9980.00</IntrBkSttlmAmt>  <!-- After fee deduction -->
    
    <!-- ChrgsInf only included if fees were deducted -->
    <ChrgsInf>
        <Amt Ccy="USD">20.00</Amt>
        <Agt>
            <FinInstnId>
                <BIC>WFBIUS6SXXX</BIC>
            </FinInstnId>
        </Agt>
    </ChrgsInf>
</CdtTrfTxInf>
```

---

## Scenarios with Examples

### Scenario 1: Inbound SWIFT from HDFC Bank (Negotiated Fee)

**Payment Details**:
- **From**: HDFC Bank India (HDFCINBB)
- **To**: Wells Fargo Customer
- **Amount**: $10,000.00
- **Network**: SWIFT
- **Direction**: INBOUND
- **Charge Bearer**: SHA (Shared)

**Fee Calculation**:
1. Fee Type: `inbound_swift`
2. Bank BIC: HDFCINBB (debtor agent)
3. Fee Lookup:
   - Check negotiated fees for HDFCINBB → Found: $20.00
   - ✅ **Negotiated Fee Applied**: $20.00 (instead of default $25.00)
4. Charge Bearer: SHA → Deduct from principal: YES
5. Settlement Amount: $10,000.00 - $20.00 = **$9,980.00**

**PACS.008 Message Fields**:
- **InstdAmt**: $10,000.00 (original amount - preserved)
- **IntrBkSttlmAmt**: $9,980.00 (settlement amount after fee deduction)
- **ChrgsInf**: Included with fee amount $20.00 and Wells Fargo BIC

**Result**:
- Fee Amount: **$20.00** (negotiated)
- InstdAmt: **$10,000.00** (preserved - original amount)
- IntrBkSttlmAmt: **$9,980.00** (after fee deduction)
- Beneficiary Receives: **$9,980.00**
- Fee is deducted from principal

---

### Scenario 2: Inbound SWIFT from Unknown Bank (Default Fee)

**Payment Details**:
- **From**: Unknown Bank (UNKNOWNXX)
- **To**: Wells Fargo Customer
- **Amount**: $10,000.00
- **Network**: SWIFT
- **Direction**: INBOUND
- **Charge Bearer**: SHA (Shared)

**Fee Calculation**:
1. Fee Type: `inbound_swift`
2. Bank BIC: UNKNOWNXX (debtor agent)
3. Fee Lookup:
   - Check negotiated fees for UNKNOWNXX → Not found
   - ✅ **Default Fee Applied**: $25.00
4. Charge Bearer: SHA → Deduct from principal: YES
5. Net Amount: $10,000.00 - $25.00 = **$9,975.00**

**Result**:
- Fee Amount: **$25.00** (default)
- Beneficiary Receives: **$9,975.00**
- Fee is deducted from principal

---

### Scenario 3: Outbound CHIPS to JPMC (Negotiated Fee)

**Payment Details**:
- **From**: Wells Fargo Customer
- **To**: JPMorgan Chase (CHASUS33)
- **Amount**: $10,000.00
- **Network**: CHIPS
- **Direction**: OUTBOUND
- **Charge Bearer**: SHA (Shared)

**Fee Calculation**:
1. Fee Type: `outbound_chips`
2. Bank BIC: CHASUS33 (creditor agent)
3. Fee Lookup:
   - Check negotiated fees for CHASUS33 → Found: $12.00
   - ✅ **Negotiated Fee Applied**: $12.00 (instead of default $15.00)
4. Charge Bearer: SHA → Deduct from principal: YES
5. Net Amount: $10,000.00 - $12.00 = **$9,988.00**

**Result**:
- Fee Amount: **$12.00** (negotiated)
- Beneficiary Receives: **$9,988.00**
- Fee is deducted from principal

---

### Scenario 4: Outbound FED to JPMC (Default Fee - No Negotiated FED Fee)

**Payment Details**:
- **From**: Wells Fargo Customer
- **To**: JPMorgan Chase (CHASUS33)
- **Amount**: $10,000.00
- **Network**: FED
- **Direction**: OUTBOUND
- **Charge Bearer**: SHA (Shared)

**Fee Calculation**:
1. Fee Type: `outbound_fed`
2. Bank BIC: CHASUS33 (creditor agent)
3. Fee Lookup:
   - Check negotiated fees for CHASUS33 → Found `outbound_chips` but NOT `outbound_fed`
   - ✅ **Default Fee Applied**: $20.00
4. Charge Bearer: SHA → Deduct from principal: YES
5. Net Amount: $10,000.00 - $20.00 = **$9,980.00**

**Result**:
- Fee Amount: **$20.00** (default - JPMC has negotiated CHIPS fee, not FED)
- Beneficiary Receives: **$9,980.00**
- Fee is deducted from principal

---

### Scenario 5: Inbound SWIFT with OUR Charge Bearer (Billing, No Deduction)

**Payment Details**:
- **From**: HDFC Bank India (HDFCINBB)
- **To**: Wells Fargo Customer
- **Amount**: $10,000.00
- **Network**: SWIFT
- **Direction**: INBOUND
- **Charge Bearer**: OUR (Sender pays)

**Fee Calculation**:
1. Fee Type: `inbound_swift`
2. Bank BIC: HDFCINBB (debtor agent)
3. Fee Lookup:
   - ✅ **Negotiated Fee Applied**: $20.00
4. Charge Bearer: OUR → Deduct from principal: **NO**
5. Settlement Amount: $10,000.00 (unchanged - fees billed separately)

**PACS.008 Message Fields**:
- **InstdAmt**: $10,000.00 (original amount - preserved)
- **IntrBkSttlmAmt**: $10,000.00 (equals InstdAmt - fees NOT deducted)
- **ChrgsInf**: NOT included (fees billed separately)

**Result**:
- Fee Amount: **$20.00** (negotiated)
- InstdAmt: **$10,000.00** (preserved - original amount)
- IntrBkSttlmAmt: **$10,000.00** (equals InstdAmt - no deduction)
- Beneficiary Receives: **$10,000.00** (full amount - fee NOT deducted)
- Fee Settlement: **BILLING** (HDFC Bank is billed separately)

---

### Scenario 6: Inbound SWIFT with CRED Charge Bearer (Receiver Pays)

**Payment Details**:
- **From**: HDFC Bank India (HDFCINBB)
- **To**: Wells Fargo Customer
- **Amount**: $10,000.00
- **Network**: SWIFT
- **Direction**: INBOUND
- **Charge Bearer**: CRED (Receiver pays)

**Fee Calculation**:
1. Fee Type: `inbound_swift`
2. Bank BIC: HDFCINBB (debtor agent)
3. Fee Lookup:
   - ✅ **Negotiated Fee Applied**: $20.00
4. Charge Bearer: CRED → Deduct from principal: **YES**
5. Net Amount: $10,000.00 - $20.00 = **$9,980.00**

**Result**:
- Fee Amount: **$20.00** (negotiated)
- Beneficiary Receives: **$9,980.00** (amount minus fee)
- Fee Settlement: **DEDUCTED** (from principal)

---

### Scenario 7: Payment with Correspondent Bank (Correspondent Processing Fee)

**Payment Details**:
- **From**: HDFC Bank India (HDFCINBB)
- **To**: Idaho Central Credit Union (ICCUUS33) - Non-FED-enabled
- **Amount**: $10,000.00
- **Network**: SWIFT → FED (via correspondent)
- **Direction**: INBOUND
- **Charge Bearer**: SHA (Shared)
- **Correspondent**: U.S. Bank (USBKUS44)

**Fee Calculation**:

**Step 1: Wells Fargo Processing Fee**
1. Fee Type: `inbound_swift`
2. Bank BIC: HDFCINBB (debtor agent)
3. Fee Lookup:
   - ✅ **Negotiated Fee Applied**: $20.00
4. Charge Bearer: SHA → Deduct from principal: YES
5. Amount after Wells fee: $10,000.00 - $20.00 = $9,980.00

**Step 2: Correspondent Processing Fee**
1. Fee Type: `correspondent_processing`
2. Bank BIC: ICCUUS33 (creditor agent - credit union)
3. Fee Lookup:
   - Check negotiated fees for ICCUUS33 → Not found
   - Check negotiated fees for SBININBB (has correspondent_processing) → Not applicable
   - ✅ **Default Fee Applied**: $10.00
4. Charge Bearer: SHA → Deduct from principal: YES
5. Final Net Amount: $9,980.00 - $10.00 = **$9,970.00**

**Result**:
- Wells Fargo Fee: **$20.00** (negotiated)
- Correspondent Fee: **$10.00** (default)
- Total Fees: **$30.00**
- Beneficiary Receives: **$9,970.00**

---

## Testing Scenarios

### Test Case 1: Verify Negotiated Fee is Applied

**Test Objective**: Verify that negotiated fees override default fees when applicable.

**Steps**:
1. Create payment: Inbound SWIFT from HDFC Bank (HDFCINBB)
2. Calculate fee
3. Verify fee amount = $20.00 (negotiated, not $25.00 default)
4. Verify `isNegotiatedFee = true`

**Expected Result**: ✅ Negotiated fee ($20.00) is applied

---

### Test Case 2: Verify Default Fee is Applied

**Test Objective**: Verify that default fees are used when no negotiated fee exists.

**Steps**:
1. Create payment: Inbound SWIFT from Unknown Bank (UNKNOWNXX)
2. Calculate fee
3. Verify fee amount = $25.00 (default)
4. Verify `isNegotiatedFee = false`

**Expected Result**: ✅ Default fee ($25.00) is applied

---

### Test Case 3: Verify Fee Type Determination

**Test Objective**: Verify that correct fee type is determined based on direction and network.

**Test Scenarios**:
- Inbound SWIFT → Fee type: `inbound_swift`
- Outbound FED → Fee type: `outbound_fed`
- Outbound CHIPS → Fee type: `outbound_chips`

**Expected Result**: ✅ Correct fee type is determined

---

### Test Case 4: Verify OUR Charge Bearer (No Deduction)

**Test Objective**: Verify that fees are NOT deducted from principal for OUR charge bearer.

**Steps**:
1. Create payment: Amount $10,000.00, Charge Bearer: OUR
2. Calculate fee: $20.00
3. Calculate net amount
4. Verify net amount = $10,000.00 (unchanged)
5. Verify `deductFromPrincipal = false`
6. Verify `feeSettlement = BILLING`

**Expected Result**: ✅ Fee is NOT deducted, principal remains unchanged

---

### Test Case 5: Verify CRED Charge Bearer (Deduction)

**Test Objective**: Verify that fees ARE deducted from principal for CRED charge bearer.

**Steps**:
1. Create payment: Amount $10,000.00, Charge Bearer: CRED
2. Calculate fee: $20.00
3. Calculate net amount
4. Verify net amount = $9,980.00 ($10,000.00 - $20.00)
5. Verify `deductFromPrincipal = true`
6. Verify `feeSettlement = DEDUCTED`

**Expected Result**: ✅ Fee is deducted from principal

---

### Test Case 6: Verify SHA Charge Bearer (Deduction)

**Test Objective**: Verify that fees ARE deducted from principal for SHA charge bearer.

**Steps**:
1. Create payment: Amount $10,000.00, Charge Bearer: SHA
2. Calculate fee: $20.00
3. Calculate net amount
4. Verify net amount = $9,980.00 ($10,000.00 - $20.00)
5. Verify `deductFromPrincipal = true`
6. Verify `feeSettlement = DEDUCTED`

**Expected Result**: ✅ Fee is deducted from principal

---

## Fee Calculation Flow Diagram

```
Payment Event
    ↓
Determine Fee Type
    ├─ Direction (inbound/outbound)
    ├─ Network (SWIFT/FED/CHIPS)
    └─ Is Correspondent? (yes/no)
    ↓
Identify Bank BIC
    ├─ Inbound → Debtor Agent BIC
    ├─ Outbound → Creditor Agent BIC
    └─ Correspondent → Creditor Agent BIC
    ↓
Lookup Fee
    ├─ Check Negotiated Fees
    │   ├─ Found? → Use Negotiated Fee
    │   └─ Not Found? → Continue
    └─ Use Default Fee
    ↓
Get Charge Bearer Code
    ├─ OUR/DEBT → deductFromPrincipal = false
    ├─ SHA/SHAR → deductFromPrincipal = true
    └─ CRED → deductFromPrincipal = true
    ↓
Calculate Net Amount
    ├─ deductFromPrincipal = true → Net = Original - Fee
    └─ deductFromPrincipal = false → Net = Original (unchanged)
    ↓
Return Fee Calculation Result
```

---

## Key Points for Testing

1. **Negotiated vs Default**: Always verify which fee schedule is being applied
2. **Charge Bearer Impact**: Test all three charge bearer codes (OUR, SHA, CRED)
3. **Fee Type Accuracy**: Verify correct fee type is determined for each scenario
4. **Net Amount Calculation**: Verify net amount is calculated correctly based on charge bearer
5. **Bank-Specific Fees**: Test banks with negotiated fees and banks without
6. **Correspondent Fees**: Test scenarios where correspondent processing is needed

---

## Summary

- **Default Fees**: Standard rates applied to all banks
- **Negotiated Fees**: Special rates for specific banks (override defaults)
- **Fee Lookup**: Negotiated fees checked first, then default fees
- **Charge Bearer Codes**: Determine who pays and how fees are applied
- **Fee Deduction**: Based on charge bearer code (OUR=no, SHA/CRED=yes)
- **Net Amount**: Calculated based on whether fee is deducted or billed separately

This fee calculation system ensures transparency, accuracy, and flexibility in fee management while supporting both standard and relationship-specific pricing.

