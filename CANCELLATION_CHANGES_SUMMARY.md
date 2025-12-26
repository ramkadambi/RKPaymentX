# Cancellation and Return - Required Changes Summary

## ✅ My Understanding - Validated

### Payment Processing Flow
```
1. Payment Received (Ingress)
   ↓
2. Account Validation
   ↓
3. Routing Validation
   ↓
4. Sanctions Check
   ↓
5. Balance Check
   ↓
6. Payment Posting
   ↓
7. Final Status
```

**On Error**: Payment goes to error queue → Human operator uses UI

### Cancellation Scenarios

#### Scenario A: camt.056 Cancellation (Instructing Bank Request)
```
Instructing Bank → camt.056 → CancellationHandler
  → PACS.002 with CANC status ✅ (CORRECT - No changes needed)
```

#### Scenario B: UI Cancellation (Business Reasons)
```
Human Operator → Error Management UI → Cancel Action
  → ErrorManagementService.cancelAndReturn()
  → Currently: PACS.002 with CANC status ❌ (WRONG)
  → Should be: PACS.002 with RJCT status + PACS.004 ✅ (REQUIRED)
```

---

## 📋 Required Changes (No Code Changes - Just Documentation)

### Change 1: Create PACS.004 Generator
**File**: `payment-common/src/main/java/com/wellsfargo/payment/notification/Pacs004Generator.java`

**Purpose**: Generate ISO 20022 PACS.004 Payment Return messages

**Key Methods**:
- `generatePacs004(PaymentEvent, returnReasonCode, additionalInfo)`
- `generateReturnId(PaymentEvent)`
- `escapeXml(String)`

**Structure** (based on sample):
- Namespace: `urn:iso:std:iso:20022:tech:xsd:pacs.004.001.09`
- Root: `<Document><PmtRtr>`
- Required fields:
  - Group Header (MsgId, CreDtTm)
  - Original Group Info (OrgnlMsgId, OrgnlMsgNmId)
  - Transaction Info (OrgnlInstrId, OrgnlEndToEndId, RtrId, RtrRsnInf, RtrAmt)

### Change 2: Create Return Reason Code Utility
**File**: `payment-common/src/main/java/com/wellsfargo/payment/notification/ReturnReasonCodeMapper.java`

**Purpose**: Map UI cancellation reasons to ISO 20022 return reason codes

**Mapping Table**:
| UI Reason | ISO 20022 Code | Description |
|-----------|---------------|-------------|
| business_rule | AG01 or RR01 | Transaction forbidden / Regulatory reason |
| sanctions | RR01 | Regulatory reason (sanctions hit) |
| insufficient_funds | AM04 | Insufficient funds |
| invalid_account | AC01 or AC04 | Account identifier incorrect / Account closed |
| customer_request | NARR | Narrative reason |
| other | NARR | Narrative reason |

**Method**:
- `mapToIso20022Code(String uiReason) → String`

### Change 3: Update ErrorManagementService.cancelAndReturn()
**File**: `payment-orchestrator/src/main/java/com/wellsfargo/payment/orchestrator/error/ErrorManagementService.java`

**Current Implementation** (Line 347-351):
```java
// Send CANC status via PACS.002
if (notificationService != null) {
    notificationService.publishStatus(paymentEvent, Pacs002Status.CANC, 
        "CANC", "Payment cancelled: " + request.getCancellationReason());
}
```

**Required Changes**:
1. Change `Pacs002Status.CANC` to `Pacs002Status.RJCT`
2. Map cancellation reason to ISO 20022 return code
3. Generate PACS.004 message
4. Publish PACS.004

**New Implementation Structure**:
```java
// 1. Map cancellation reason to ISO 20022 return code
String returnReasonCode = ReturnReasonCodeMapper.mapToIso20022Code(
    request.getCancellationReason()
);
String returnReasonText = request.getCancellationReason() + ": " + 
    (request.getComments() != null ? request.getComments() : "");

// 2. Send PACS.002 with RJCT status (not CANC)
if (notificationService != null) {
    notificationService.publishStatus(paymentEvent, Pacs002Status.RJCT, 
        returnReasonCode, "Payment rejected: " + returnReasonText);
}

// 3. Generate PACS.004 payment return message
Pacs004Generator pacs004Generator = new Pacs004Generator();
String pacs004Xml = pacs004Generator.generatePacs004(
    paymentEvent, 
    returnReasonCode, 
    returnReasonText
);

// 4. Publish PACS.004 to notification topic
publishPacs004(paymentEvent, pacs004Xml);
```

### Change 4: Add PACS.004 Publishing Method
**File**: `payment-orchestrator/src/main/java/com/wellsfargo/payment/orchestrator/error/ErrorManagementService.java`

**New Method**:
```java
/**
 * Publish PACS.004 payment return message.
 * 
 * @param event PaymentEvent
 * @param pacs004Xml PACS.004 XML message
 */
private void publishPacs004(PaymentEvent event, String pacs004Xml) {
    String endToEndId = event.getEndToEndId();
    
    // Publish to notification topic (same as PACS.002)
    // Alternative: Create dedicated topic or route to egress service
    ProducerRecord<String, String> record = new ProducerRecord<>(
        "payments.notification", endToEndId, pacs004Xml);
    
    eventProducer.send(record, (metadata, exception) -> {
        if (exception != null) {
            log.error("Failed to publish PACS.004 - E2E={}", endToEndId, exception);
        } else {
            log.info("Published PACS.004 - E2E={}, topic={}, partition={}, offset={}", 
                endToEndId, metadata.topic(), metadata.partition(), metadata.offset());
        }
    });
}
```

**Note**: May need to use String producer instead of PaymentEvent producer for PACS.004 XML

### Change 5: Update NotificationService (Optional)
**File**: `payment-common/src/main/java/com/wellsfargo/payment/notification/NotificationService.java`

**Consideration**: 
- Add method `publishPacs004(PaymentEvent, String pacs004Xml)`
- OR keep PACS.004 publishing in ErrorManagementService
- Decision: Keep in ErrorManagementService for now (simpler)

### Change 6: Update Kafka Topics Documentation
**File**: `KAFKA_TOPICS.md`

**Add Section**:
```
### Payment Return Topic

payments.notification              # PACS.002 status reports AND PACS.004 return messages
                                  # Published by: NotificationService (PACS.002) and ErrorManagementService (PACS.004)
                                  # Consumed by: Egress services (for SWIFT delivery)
```

---

## 🔄 Flow Comparison

### Current Flow (UI Cancellation - WRONG)
```
User clicks "Cancel & Return" in UI
  ↓
ErrorManagementService.cancelAndReturn()
  ↓
Send PACS.002 with CANC status
  ↓
Done ❌ (Missing PACS.004)
```

### Required Flow (UI Cancellation - CORRECT)
```
User clicks "Cancel & Return" in UI
  ↓
ErrorManagementService.cancelAndReturn()
  ↓
1. Map cancellation reason to ISO 20022 code
  ↓
2. Send PACS.002 with RJCT status
  ↓
3. Generate PACS.004 message
  ↓
4. Publish PACS.004 to notification topic
  ↓
Done ✅
```

### Current Flow (camt.056 Cancellation - CORRECT)
```
Instructing bank sends camt.056
  ↓
CancellationHandler.handleCancellationRequest()
  ↓
Send PACS.002 with CANC status
  ↓
Done ✅ (No changes needed)
```

---

## 📊 Message Flow Diagram

### UI Cancellation (After Changes)
```
┌─────────────────┐
│ Error UI        │
│ Cancel Action   │
└────────┬────────┘
         │
         ▼
┌─────────────────────────────┐
│ ErrorManagementService       │
│ cancelAndReturn()           │
└────────┬──────────────────────┘
         │
         ├─► PACS.002 Generator
         │   Status: RJCT
         │   Reason: {ISO code}
         │
         └─► PACS.004 Generator
             Return ID: {generated}
             Return Reason: {ISO code}
             Return Amount: {original amount}
         │
         ▼
┌─────────────────────────────┐
│ payments.notification topic  │
│ (contains both messages)     │
└────────┬──────────────────────┘
         │
         ▼
┌─────────────────────────────┐
│ Egress Service (SWIFT)       │
│ Delivers to Instructing Bank │
└─────────────────────────────┘
```

---

## 🎯 Key Points

1. **CANC vs RJCT**:
   - **CANC**: Payment cancelled before processing (camt.056 request)
   - **RJCT**: Payment rejected/cancelled after processing attempt (UI cancellation)

2. **PACS.002 vs PACS.004**:
   - **PACS.002**: Status notification (always sent)
   - **PACS.004**: Payment return message (only for UI cancellations requiring fund return)

3. **Reason Codes**:
   - PACS.002 uses status codes (RCVD, ACCP, RJCT, etc.)
   - PACS.004 uses ISO 20022 return reason codes (AC01, AC04, RR01, etc.)

4. **Delivery**:
   - Both PACS.002 and PACS.004 go to instructing bank
   - Can be published to same topic (`payments.notification`)
   - Egress service routes to appropriate network (SWIFT, FED, etc.)

---

## ✅ Validation

### Understanding Confirmed:
- ✅ Payment processes as much as possible before error
- ✅ Human operator uses UI to view and fix errors
- ✅ Operator can fix & resume OR restart from beginning
- ✅ Operator can cancel for business reasons
- ✅ UI cancellation should send PACS.002 (RJCT) + PACS.004
- ✅ camt.056 cancellation sends PACS.002 (CANC) only (correct)

### Changes Identified:
1. ✅ Create Pacs004Generator
2. ✅ Create ReturnReasonCodeMapper
3. ✅ Update ErrorManagementService.cancelAndReturn() to use RJCT
4. ✅ Add PACS.004 generation and publishing
5. ✅ Update documentation

---

## 📝 Implementation Checklist

- [ ] Create `Pacs004Generator.java`
- [ ] Create `ReturnReasonCodeMapper.java` (or utility method)
- [ ] Update `ErrorManagementService.cancelAndReturn()`:
  - [ ] Change CANC to RJCT
  - [ ] Add reason code mapping
  - [ ] Add PACS.004 generation
  - [ ] Add PACS.004 publishing
- [ ] Add `publishPacs004()` method
- [ ] Update `KAFKA_TOPICS.md`
- [ ] Test cancellation flow
- [ ] Verify PACS.002 and PACS.004 messages are generated correctly

---

**Status**: Understanding validated. All required changes identified. Ready for implementation.

