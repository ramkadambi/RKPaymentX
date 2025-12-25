# Payment Posting Service Implementation

## Overview

The Payment Posting Service has been implemented based on the SwiftProcessing reference project. It enforces idempotency using end_to_end_id, produces explicit debit/credit entries, and prepares structure for MongoDB persistence.

## Implementation Details

### Files Created/Updated

1. **`satellites/payment_posting.py`** - Payment posting service
   - Enforces idempotency using end_to_end_id
   - Produces explicit debit/credit entries
   - Prepares MongoDB document structure
   - No actual DB persistence yet (structure ready)

2. **`satellites/__init__.py`** - Updated exports

## Key Features

### 1. Idempotency Enforcement

The service enforces idempotency by:
- Checking if transaction already processed using `end_to_end_id`
- Using `end_to_end_id` as unique identifier
- In production: MongoDB unique index on `end_to_end_id`
- In production: Redis cache for fast idempotency checks

```python
# Idempotency check
if is_transaction_processed(event.end_to_end_id):
    return ERROR  # Transaction already processed
```

### 2. Explicit Debit/Credit Entries

The service creates explicit `PostingEntry` objects:

```python
@dataclass
class PostingEntry:
    end_to_end_id: str
    side: EntrySide  # DEBIT or CREDIT
    agent_id: str
    amount: Decimal
    currency: str
    settlement_account: Optional[str]
    account_type: Optional[str]  # VOSTRO, FED, CHIPS_NOSTRO, etc.
    timestamp: Optional[str]
```

Each payment creates:
- **Debit Entry**: Debits from source account (Vostro, FED, CHIPS Nostro, etc.)
- **Credit Entry**: Credits to destination account (FED, CHIPS Nostro, SWIFT Nostro, etc.)

### 3. MongoDB Document Structure

The service prepares `TransactionDocument` for MongoDB persistence:

```python
@dataclass
class TransactionDocument:
    end_to_end_id: str  # Primary key for idempotency
    transaction_id: Optional[str]
    msg_id: str
    amount: Decimal
    currency: str
    debit_entry: Optional[Dict[str, Any]]
    credit_entry: Optional[Dict[str, Any]]
    routing_network: Optional[str]
    status: str  # POSTED
    posted_timestamp: Optional[str]
    created_timestamp: Optional[str]
```

## Service Flow

```
1. Consume PaymentEvent from Kafka
   ↓
2. Check idempotency (is_transaction_processed)
   - If already processed → ERROR
   ↓
3. Create explicit debit/credit entries
   - Determine settlement accounts from routing decision
   - Create PostingEntry for DEBIT
   - Create PostingEntry for CREDIT
   ↓
4. Prepare MongoDB document structure
   - Create TransactionDocument
   - Convert PostingEntry objects to dictionaries
   ↓
5. Persist to MongoDB (structure prepared, no DB yet)
   - Store in in-memory cache for testing
   - In production: Insert to MongoDB with unique index on end_to_end_id
   - In production: Update Redis cache for fast lookups
   ↓
6. Enrich PaymentEvent with settlement accounts
   ↓
7. Publish ServiceResult (PASS/FAIL/ERROR)
```

## Settlement Account Determination

The service uses the same logic as `balance_check` service to determine settlement accounts:

- **INTERNAL (IBT)**: Vostro account (inbound) or customer account
- **FED**: FED settlement account (credit), Vostro or customer (debit)
- **CHIPS**: CHIPS nostro account (credit), Vostro or customer (debit)
- **SWIFT**: SWIFT nostro account (credit for outbound), Vostro (debit for inbound)

## Idempotency Implementation

### Current (Testing)
- In-memory cache: `_transaction_cache[end_to_end_id]`
- Check: `if end_to_end_id in _transaction_cache`

### Production (MongoDB)
```java
// MongoDB unique index on end_to_end_id
db.transactions.createIndex({ "end_to_end_id": 1 }, { unique: true });

// Idempotency check
Document existing = mongoCollection.findOne(
    new Document("end_to_end_id", endToEndId)
);
if (existing != null) {
    return ERROR; // Already processed
}
```

### Production (Redis)
```java
// Fast idempotency check
Boolean exists = redisClient.exists("transaction:" + endToEndId);
if (exists) {
    return ERROR; // Already processed
}
```

## MongoDB Document Structure

The `TransactionDocument` will be persisted to MongoDB with:

- **Collection**: `transactions`
- **Primary Key**: `end_to_end_id` (unique index)
- **Indexes**: 
  - `end_to_end_id` (unique)
  - `transaction_id` (optional)
  - `posted_timestamp` (for queries)
  - `routing_network` (for analytics)

## Enrichment Data

The service enriches PaymentEvent with settlement accounts in `enrichment_context.settlement_accounts`:

```python
{
    "debit_account": "WF-VOSTRO-SBI-USD-001",
    "credit_account": "WF-FED-SETTLE-001",
    "debit_account_type": "VOSTRO",
    "credit_account_type": "FED"
}
```

## Business Rules

1. **Idempotency**: Transaction with same `end_to_end_id` can only be posted once
2. **Amount Threshold**: FAIL if amount > 25000 (simple control)
3. **Legacy Rule**: FAIL if `end_to_end_id` ends with 'Z'
4. **Double-Entry**: Always creates both DEBIT and CREDIT entries

## Kafka Topics

- **Input Topic**: `payments.step.payment_posting`
- **Result Topic**: `service.results.payment_posting`
- **Error Topic**: `service.errors.payment_posting`

## Orchestrator Sequencing

The service maintains orchestrator sequencing by:
1. Publishing `ServiceResult` to the result topic (orchestrator consumes this)
2. Publishing error `ServiceResult` to error topic if FAIL/ERROR

**Note**: Orchestrator sequencing is NOT changed - the service follows the same pattern as the reference implementation.

## Java Implementation Notes

The Python implementation serves as a reference. For Java implementation:

1. Convert dataclasses to Java classes (using Lombok or manual)
2. Implement MongoDB client using MongoDB Java driver
3. Implement Redis client using Jedis or Lettuce
4. Create MongoDB unique index on `end_to_end_id`
5. Implement idempotency check (MongoDB + Redis)
6. Implement transaction persistence to MongoDB
7. Implement Kafka consumer/producer using Kafka Java client
8. Add unit tests for idempotency
9. Add integration tests for MongoDB persistence

## MongoDB Schema Example

```json
{
  "_id": ObjectId("..."),
  "end_to_end_id": "E2E-001",
  "transaction_id": "TXN-001",
  "msg_id": "MSG-001",
  "amount": 5000.00,
  "currency": "USD",
  "debit_entry": {
    "end_to_end_id": "E2E-001",
    "side": "DEBIT",
    "agent_id": "SBININBB",
    "amount": 5000.00,
    "currency": "USD",
    "settlement_account": "WF-VOSTRO-SBI-USD-001",
    "account_type": "VOSTRO",
    "timestamp": "2025-12-23T13:48:00.000Z"
  },
  "credit_entry": {
    "end_to_end_id": "E2E-001",
    "side": "CREDIT",
    "agent_id": "WFBIUS6S",
    "amount": 5000.00,
    "currency": "USD",
    "settlement_account": "WFBIUS6S",
    "account_type": "CUSTOMER",
    "timestamp": "2025-12-23T13:48:00.000Z"
  },
  "routing_network": "INTERNAL",
  "status": "POSTED",
  "posted_timestamp": "2025-12-23T13:48:00.000Z",
  "created_timestamp": "2025-12-23T13:47:00.000Z"
}
```

## Testing

To test the service:

1. Create a PaymentEvent with `routing_context.selected_network` set
2. Call `PaymentPostingService._handle_event(event)`
3. Verify:
   - ServiceResult is created with correct status
   - PostingEntry objects are created (DEBIT and CREDIT)
   - TransactionDocument is prepared for MongoDB
   - Idempotency check works (second call with same end_to_end_id returns ERROR)
   - Settlement accounts are correctly determined

## Example Posting Entries

### Scenario: Inbound FED Payment
- **Debit Entry**: 
  - Account: `WF-VOSTRO-SBI-USD-001` (VOSTRO)
  - Amount: 5000.00 USD
- **Credit Entry**:
  - Account: `WF-FED-SETTLE-001` (FED)
  - Amount: 5000.00 USD

### Scenario: Outbound CHIPS Payment
- **Debit Entry**:
  - Account: `CHASUS33` (CUSTOMER)
  - Amount: 8000.00 USD
- **Credit Entry**:
  - Account: `WF-CHIPS-NOSTRO-CHASE-001` (CHIPS_NOSTRO)
  - Amount: 8000.00 USD

