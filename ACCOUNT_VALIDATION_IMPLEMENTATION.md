# Account Validation Service Implementation

## Overview

The Account Validation Service has been implemented based on the SwiftProcessing reference project. It enriches PaymentEvent with account validation data and publishes the enriched event forward.

## Implementation Details

### Files Created/Updated

1. **`canonical/models.py`** - Updated `EnrichmentContext` documentation
   - Updated `account_validation` field documentation to include `nostro_accounts_available`
   - Changed from `nostro_with_us` to `nostro_accounts_available` for clarity

2. **`satellites/account_lookup.py`** - Account lookup service with mock reference data
   - Provides `lookup_account_enrichment()` function
   - Provides `enrich_payment_event()` function
   - Contains mock reference data map for testing

3. **`satellites/account_validation.py`** - Account validation service
   - Validates payment accounts
   - Enriches PaymentEvent with account validation data
   - Publishes enriched PaymentEvent to routing validation topic
   - Publishes ServiceResult for orchestrator sequencing

## Enrichment Fields

The AccountValidationService enriches PaymentEvent with the following fields in `enrichment_context.account_validation`:

1. **creditor_type**: `CreditorType` enum (BANK, INDIVIDUAL, CORPORATE)
2. **fed_member**: `bool` - Whether the creditor bank is a FED member
3. **chips_member**: `bool` - Whether the creditor bank is a CHIPS member
4. **preferred_correspondent**: `Optional[str]` - Preferred correspondent BIC
5. **nostro_accounts_available**: `bool` - Whether nostro accounts are available with this bank

## Service Flow

```
1. Consume PaymentEvent from Kafka (payments.step.account_validation)
   ↓
2. Validate account (simulate_account_validation)
   ↓
3. If PASS:
   - Lookup account enrichment data (account_lookup)
   - Create enriched PaymentEvent with enrichment_context.account_validation
   - Publish enriched PaymentEvent to routing validation topic
   ↓
4. Publish ServiceResult to result topic (for orchestrator sequencing)
   ↓
5. If FAIL/ERROR:
   - Publish ServiceResult to error topic
```

## Mock Reference Data

The `account_lookup.py` contains mock reference data for the following banks:

- **US Banks (FED members)**: WFBIUS6S, CHASUS33, BOFAUS3N, USBKUS44
- **Foreign Banks (CHIPS members)**: DEUTDEFF, HSBCGB2L
- **Foreign Banks (no CHIPS)**: SBININBB, BAMXMXMM
- **Individual accounts**: INDIVIDUAL

## Kafka Topics

- **Input Topic**: `payments.step.account_validation`
- **Result Topic**: `service.results.account_validation`
- **Error Topic**: `service.errors.account_validation`
- **Routing Topic**: `payments.step.routing_validation` (enriched PaymentEvent)

## Orchestrator Sequencing

The service maintains orchestrator sequencing by:
1. Publishing `ServiceResult` to the result topic (orchestrator consumes this)
2. Publishing enriched `PaymentEvent` to routing validation topic (next satellite consumes this)

**Note**: Orchestrator sequencing is NOT changed - the service follows the same pattern as the reference implementation.

## Java Implementation Notes

The Python implementation serves as a reference. For Java implementation:

1. Convert dataclasses to Java classes (using Lombok or manual)
2. Implement Kafka consumer/producer using Kafka Java client
3. Implement JSON serialization/deserialization (Jackson, Gson, etc.)
4. Replace mock data map with MongoDB/Redis lookup
5. Implement proper error handling and logging
6. Add unit tests

## Testing

To test the service:

1. Create a PaymentEvent with a creditor_agent BIC
2. Call `AccountValidationService._handle_event(event)`
3. Verify:
   - ServiceResult is created with correct status
   - Enriched PaymentEvent has `enrichment_context.account_validation` populated
   - All required fields are present: creditor_type, fed_member, chips_member, preferred_correspondent, nostro_accounts_available

## Example Enrichment Data

```python
{
    "status": "PASS",
    "creditor_type": "BANK",
    "fed_member": True,
    "chips_member": True,
    "nostro_accounts_available": True,
    "preferred_correspondent": "CHASUS33XXX"
}
```

