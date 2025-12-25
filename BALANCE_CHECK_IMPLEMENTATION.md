# Balance Check Service Implementation

## Overview

The Balance Check Service has been implemented based on the SwiftProcessing reference project. It determines the debit account based on routing decisions and checks balance using test_ledger.json as a mock data source.

## Implementation Details

### Files Created

1. **`satellites/balance_check.py`** - Balance check service
   - Determines debit account based on routing decision
   - Supports FED, CHIPS, SWIFT Nostro, and Vostro accounts
   - Uses test_ledger.json as mock data source (read-only)
   - Enriches PaymentEvent with balance check results

2. **`satellites/settlement_account_lookup.py`** - Settlement account lookup service
   - Provides lookup functions for Vostro, FED, CHIPS Nostro, and SWIFT Nostro accounts
   - Mock reference data for testing

3. **`satellites/ledger_service.py`** - Ledger service (read-only)
   - Reads account balances from test_ledger.json
   - Provides `get_account_balance()`, `has_sufficient_balance()`, `account_exists()`
   - **No persistence** - read-only for balance checking

4. **`data/test_ledger.json`** - Test ledger data
   - Copied from SwiftProcessing project
   - Contains account balances for all test accounts

## Debit Account Determination Logic

The service determines the debit account based on:

1. **Routing Network** (from `routing_context.selected_network`)
2. **Payment Direction** (INBOUND, OUTBOUND, INTERNAL)
3. **Settlement Account Lookup** (Vostro, FED, CHIPS Nostro, SWIFT Nostro)

### Account Types Supported

- **VOSTRO**: Foreign bank accounts maintained at Wells Fargo (for inbound payments)
- **FED**: Wells Fargo's Federal Reserve settlement account
- **CHIPS_NOSTRO**: CHIPS participant accounts maintained at Wells Fargo
- **SWIFT_NOSTRO**: Foreign bank accounts maintained at Wells Fargo (for outbound SWIFT)
- **CUSTOMER**: Customer accounts (fallback)

### Routing Network Logic

#### INTERNAL (IBT)
- **Inbound**: Debit from foreign bank's vostro account
- **Outbound/Internal**: Debit from customer account (debtor BIC)

#### FED
- **Inbound**: Debit from foreign bank's vostro account
- **Outbound**: Debit from FED settlement account (`WF-FED-SETTLE-001`)

#### CHIPS
- **Inbound**: Debit from foreign bank's vostro account
- **Outbound**: Debit from CHIPS nostro account (if relationship exists), otherwise FED account

#### SWIFT
- **Inbound**: Debit from foreign bank's vostro account
- **Outbound**: Debit from SWIFT nostro account (if exists), otherwise customer account

## Balance Check Flow

```
1. Consume PaymentEvent from Kafka
   ↓
2. Determine debit account from routing decision
   - Extract routing network from routing_context
   - Determine payment direction (inbound/outbound)
   - Lookup settlement account (Vostro, FED, CHIPS Nostro, SWIFT Nostro)
   ↓
3. Check account exists in ledger
   ↓
4. Get account balance from test_ledger.json
   ↓
5. Check if balance >= required amount
   ↓
6. Enrich PaymentEvent with balance check results
   ↓
7. Publish ServiceResult (PASS/FAIL/ERROR)
```

## Enrichment Data

The service enriches PaymentEvent with balance check data in `enrichment_context.balance_check`:

```python
{
    "status": "PASS" | "FAIL" | "ERROR",
    "debit_account_id": "WF-VOSTRO-SBI-USD-001",
    "account_type": "VOSTRO",
    "available_balance": 1000000.0,
    "required_amount": 5000.0,
    "sufficient_funds": true
}
```

## Test Ledger Structure

The `test_ledger.json` contains:

- **FED Account**: `WF-FED-SETTLE-001`
- **Vostro Accounts**: `WF-VOSTRO-*-USD-001` (for various foreign banks)
- **CHIPS Nostro Accounts**: `WF-CHIPS-NOSTRO-*-001` (for CHIPS participants)
- **SWIFT Nostro Accounts**: `WF-SWIFT-NOSTRO-*-001` (for foreign banks)
- **Customer Accounts**: BIC codes (e.g., `CHASUS33`, `BOFAUS3N`)

## Service Rules

1. **PASS**: Account exists and has sufficient balance
2. **FAIL**: Account doesn't exist OR insufficient balance
3. **ERROR**: Account lookup failed or ledger read error

## Kafka Topics

- **Input Topic**: `payments.step.balance_check`
- **Result Topic**: `service.results.balance_check`
- **Error Topic**: `service.errors.balance_check`

## Orchestrator Sequencing

The service maintains orchestrator sequencing by:
1. Publishing `ServiceResult` to the result topic (orchestrator consumes this)
2. Publishing error `ServiceResult` to error topic if FAIL/ERROR

**Note**: Orchestrator sequencing is NOT changed - the service follows the same pattern as the reference implementation.

## Java Implementation Notes

The Python implementation serves as a reference. For Java implementation:

1. Convert dataclasses to Java classes (using Lombok or manual)
2. Implement JSON parsing for test_ledger.json (Jackson, Gson, etc.)
3. Implement Kafka consumer/producer using Kafka Java client
4. Replace file-based ledger with MongoDB/Redis lookup
5. Implement proper error handling and logging
6. Add unit tests for debit account determination logic
7. Add integration tests for balance checking

## Testing

To test the service:

1. Create a PaymentEvent with `routing_context.selected_network` set
2. Ensure the debit account exists in `test_ledger.json`
3. Call `BalanceCheckService._handle_event(event)`
4. Verify:
   - ServiceResult is created with correct status
   - Enriched PaymentEvent has `enrichment_context.balance_check` populated
   - Correct debit account is determined based on routing network
   - Balance check passes/fails based on available balance

## Example Scenarios

### Scenario 1: Inbound FED Payment
- **Routing Network**: FED
- **Direction**: INBOUND (foreign bank → Wells Fargo)
- **Debit Account**: Vostro account of foreign bank
- **Example**: `WF-VOSTRO-SBI-USD-001`

### Scenario 2: Outbound FED Payment
- **Routing Network**: FED
- **Direction**: OUTBOUND (Wells Fargo → US bank)
- **Debit Account**: FED settlement account
- **Example**: `WF-FED-SETTLE-001`

### Scenario 3: Outbound CHIPS Payment
- **Routing Network**: CHIPS
- **Direction**: OUTBOUND (Wells Fargo → CHIPS participant)
- **Debit Account**: CHIPS nostro account
- **Example**: `WF-CHIPS-NOSTRO-CHASE-001`

### Scenario 4: Outbound SWIFT Payment
- **Routing Network**: SWIFT
- **Direction**: OUTBOUND (Wells Fargo → foreign bank)
- **Debit Account**: SWIFT nostro account
- **Example**: `WF-SWIFT-NOSTRO-BANAMEX-001`

