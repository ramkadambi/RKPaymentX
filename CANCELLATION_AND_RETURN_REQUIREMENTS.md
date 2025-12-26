# Cancellation and Return Requirements - Analysis

## My Understanding

### Current Payment Flow
1. **Payment Processing**: System tries to process payment through all steps
2. **Error Handling**: When errors occur, payment goes to error queue
3. **Human Intervention**: Operator uses UI to:
   - View error details
   - Fix the issue and resume from failed step
   - OR restart from beginning (account validation)
   - OR cancel the payment (business reasons)

### Cancellation Scenarios

#### Scenario 1: Cancellation via camt.056 (Instructing Bank Request)
- **Trigger**: Instructing bank sends camt.056 cancellation request
- **Current Behavior**: 
  - `CancellationHandler` processes request
  - Sends PACS.002 with **CANC** status
  - Removes payment from processing
- **Status**: ✅ Correct - CANC is appropriate for cancellation requests

#### Scenario 2: Cancellation via UI (Business Reasons)
- **Trigger**: Human operator cancels payment via Error Management UI
- **Current Behavior**:
  - `ErrorManagementService.cancelAndReturn()` sends PACS.002 with **CANC** status
- **Required Behavior**:
  - Send PACS.002 with **RJCT** status (rejection, not cancellation)
  - Send PACS.004 (Payment Return message) to return funds
  - Both messages should go to instructing bank

### Key Differences

| Aspect | CANC (camt.056) | RJCT (UI Cancellation) |
|--------|----------------|----------------------|
| **Trigger** | Instructing bank requests cancellation | Wells operator cancels due to business reason |
| **Status** | CANC - Cancelled | RJCT - Rejected |
| **PACS.002** | Yes, with CANC | Yes, with RJCT |
| **PACS.004** | No (payment not processed) | Yes (funds need to be returned) |
| **Reason** | Customer/instructing bank request | Business rule violation, sanctions, etc. |

---

## Required Changes

### 1. Create PACS.004 Generator
**Location**: `payment-common/src/main/java/com/wellsfargo/payment/notification/Pacs004Generator.java`

**Purpose**: Generate ISO 20022 compliant PACS.004 (Payment Return) messages

**Structure** (based on sample):
```xml
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.004.001.09">
  <PmtRtr>
    <GrpHdr>
      <MsgId>WF-RETURN-{timestamp}-{id}</MsgId>
      <CreDtTm>{timestamp}</CreDtTm>
    </GrpHdr>
    <OrgnlGrpInf>
      <OrgnlMsgId>{original message ID}</OrgnlMsgId>
      <OrgnlMsgNmId>{pacs.008.001.08 or pacs.009.001.08}</OrgnlMsgNmId>
    </OrgnlGrpInf>
    <TxInf>
      <OrgnlInstrId>{original instruction ID}</OrgnlInstrId>
      <OrgnlEndToEndId>{endToEndId}</OrgnlEndToEndId>
      <RtrId>{return ID}</RtrId>
      <RtrRsnInf>
        <Rsn>
          <Cd>{ISO 20022 reason code}</Cd>
        </Rsn>
        <AddtlInf>{additional information}</AddtlInf>
      </RtrRsnInf>
      <RtrAmt Ccy="{currency}">{amount}</RtrAmt>
    </TxInf>
  </PmtRtr>
</Document>
```

**Required Fields**:
- Message ID (generated)
- Creation date/time
- Original message ID (from PaymentEvent.msgId)
- Original message name ID (pacs.008 or pacs.009)
- Original instruction ID (from PaymentEvent.endToEndId)
- Original end-to-end ID
- Return ID (generated)
- Return reason code (ISO 20022 code like AC01, AC04, RR01, etc.)
- Return amount and currency

### 2. Create Return Reason Code Mapping
**Location**: `payment-common/src/main/java/com/wellsfargo/payment/canonical/enums/ReturnReasonCode.java`

**Purpose**: Map UI cancellation reasons to ISO 20022 return reason codes

**Mapping**:
- "business_rule" → RR01 (Regulatory reason) or AG01 (Transaction forbidden)
- "sanctions" → RR01 (Regulatory reason)
- "insufficient_funds" → AM04 (Insufficient funds)
- "invalid_account" → AC01 (Account identifier incorrect) or AC04 (Account closed)
- "customer_request" → NARR (Narrative reason)
- "other" → NARR (Narrative reason)

### 3. Update ErrorManagementService.cancelAndReturn()
**Location**: `payment-orchestrator/src/main/java/com/wellsfargo/payment/orchestrator/error/ErrorManagementService.java`

**Current Code** (Line 333-355):
```java
public void cancelAndReturn(ErrorActionRequest request, PaymentEvent paymentEvent) {
    // ... error validation ...
    
    // Send CANC status via PACS.002
    if (notificationService != null) {
        notificationService.publishStatus(paymentEvent, Pacs002Status.CANC, 
            "CANC", "Payment cancelled: " + request.getCancellationReason());
    }
}
```

**Required Changes**:
1. Change PACS.002 status from **CANC** to **RJCT**
2. Map cancellation reason to ISO 20022 return reason code
3. Generate PACS.004 message using Pacs004Generator
4. Publish PACS.004 to notification topic (or egress topic for SWIFT delivery)

**New Code Structure**:
```java
public void cancelAndReturn(ErrorActionRequest request, PaymentEvent paymentEvent) {
    // ... error validation ...
    
    // Map cancellation reason to ISO 20022 return reason code
    String returnReasonCode = mapCancellationReasonToReturnCode(request.getCancellationReason());
    String returnReasonText = request.getCancellationReason() + ": " + 
        (request.getComments() != null ? request.getComments() : "");
    
    // 1. Send PACS.002 with RJCT status
    if (notificationService != null) {
        notificationService.publishStatus(paymentEvent, Pacs002Status.RJCT, 
            returnReasonCode, "Payment rejected: " + returnReasonText);
    }
    
    // 2. Generate and send PACS.004 (Payment Return)
    Pacs004Generator pacs004Generator = new Pacs004Generator();
    String pacs004Xml = pacs004Generator.generatePacs004(
        paymentEvent, 
        returnReasonCode, 
        returnReasonText
    );
    
    // Publish PACS.004 to notification topic (or egress topic)
    publishPacs004(paymentEvent, pacs004Xml);
}
```

### 4. Add PACS.004 Publishing Method
**Location**: `payment-orchestrator/src/main/java/com/wellsfargo/payment/orchestrator/error/ErrorManagementService.java`

**New Method**:
```java
/**
 * Publish PACS.004 payment return message.
 */
private void publishPacs004(PaymentEvent event, String pacs004Xml) {
    // Option 1: Publish to notification topic (same as PACS.002)
    // Option 2: Publish to egress topic for SWIFT delivery
    // Option 3: Create dedicated topic: payments.return.pacs004
    
    // For now, publish to notification topic
    // In production, may need to route to appropriate egress service
}
```

### 5. Update NotificationService (Optional Enhancement)
**Location**: `payment-common/src/main/java/com/wellsfargo/payment/notification/NotificationService.java`

**Consideration**: 
- Add method to publish PACS.004 messages
- OR create separate ReturnNotificationService
- OR extend existing NotificationService to handle both PACS.002 and PACS.004

### 6. Update Kafka Topics Documentation
**Location**: `KAFKA_TOPICS.md`

**Add**:
- `payments.return.pacs004` (optional - if separate topic needed)
- Or document that PACS.004 goes to `payments.notification` topic

---

## Detailed Change Summary

### Files to Create:
1. ✅ `payment-common/src/main/java/com/wellsfargo/payment/notification/Pacs004Generator.java`
2. ✅ `payment-common/src/main/java/com/wellsfargo/payment/canonical/enums/ReturnReasonCode.java` (optional - can be utility method)

### Files to Modify:
1. ✅ `payment-orchestrator/src/main/java/com/wellsfargo/payment/orchestrator/error/ErrorManagementService.java`
   - Change `cancelAndReturn()` method:
     - Change PACS.002 status from CANC to RJCT
     - Add PACS.004 generation
     - Add PACS.004 publishing
   - Add `publishPacs004()` method
   - Add `mapCancellationReasonToReturnCode()` method

2. ✅ `payment-common/src/main/java/com/wellsfargo/payment/notification/NotificationService.java` (optional)
   - Add `publishPacs004()` method if needed

3. ✅ `KAFKA_TOPICS.md`
   - Document PACS.004 message flow

### Logic Changes:

#### Current Flow (UI Cancellation):
```
User cancels via UI
  → ErrorManagementService.cancelAndReturn()
  → Send PACS.002 with CANC status
  → Done
```

#### Required Flow (UI Cancellation):
```
User cancels via UI
  → ErrorManagementService.cancelAndReturn()
  → Send PACS.002 with RJCT status
  → Generate PACS.004 message
  → Publish PACS.004 to notification/egress topic
  → Done
```

#### Current Flow (camt.056 Cancellation):
```
Instructing bank sends camt.056
  → CancellationHandler.handleCancellationRequest()
  → Send PACS.002 with CANC status
  → Done
```
**Status**: ✅ Correct - No changes needed

---

## PACS.004 Message Details

### Purpose
PACS.004 is used to return funds that were previously credited or to reject a payment that cannot be processed.

### Key Elements:
1. **OrgnlGrpInf**: References original payment message
2. **TxInf**: Transaction return information
   - `OrgnlInstrId`: Original instruction ID
   - `OrgnlEndToEndId`: Original end-to-end ID
   - `RtrId`: Return ID (unique identifier for this return)
   - `RtrRsnInf`: Return reason information
     - `Cd`: ISO 20022 reason code (AC01, AC04, RR01, etc.)
     - `AddtlInf`: Additional information
   - `RtrAmt`: Return amount and currency

### ISO 20022 Return Reason Codes:
- **AC01**: Account identifier incorrect
- **AC04**: Account closed
- **AC06**: Account blocked
- **AM04**: Insufficient funds
- **AM05**: Duplicate payment
- **BE04**: Incorrect creditor name
- **RR01**: Regulatory reason (sanctions, AML)
- **RR02**: Regulatory reporting incomplete
- **AG01**: Transaction forbidden
- **AG02**: Invalid bank operation
- **NARR**: Narrative reason (free text)

---

## Validation Checklist

### ✅ Understanding Confirmed:
1. ✅ Payment system processes as much as possible before error
2. ✅ Human operator uses UI to view errors and take action
3. ✅ Operator can fix & resume OR restart from beginning
4. ✅ Operator can cancel for business reasons
5. ✅ When cancelled via UI, should send:
   - ✅ PACS.002 with **RJCT** status (not CANC)
   - ✅ PACS.004 payment return message
6. ✅ Both messages go to instructing bank
7. ✅ camt.056 cancellation (from instructing bank) uses CANC status (correct)

### ⚠️ Changes Needed:
1. ⚠️ Change UI cancellation to use RJCT instead of CANC
2. ⚠️ Create PACS.004 generator
3. ⚠️ Add PACS.004 publishing logic
4. ⚠️ Map cancellation reasons to ISO 20022 return codes
5. ⚠️ Update documentation

---

## Implementation Approach

### Phase 1: Create PACS.004 Generator
- Similar structure to Pacs002Generator
- Generate ISO 20022 compliant XML
- Include all required fields from PaymentEvent

### Phase 2: Update Cancellation Logic
- Modify ErrorManagementService.cancelAndReturn()
- Change CANC to RJCT
- Add PACS.004 generation and publishing

### Phase 3: Reason Code Mapping
- Create utility to map UI cancellation reasons to ISO codes
- Handle edge cases and default to NARR if unknown

### Phase 4: Integration
- Ensure PACS.004 messages are routed correctly
- Update egress services if needed to handle PACS.004
- Update documentation

---

## Summary

**Current State**: 
- UI cancellation sends PACS.002 with CANC status
- No PACS.004 generation

**Required State**:
- UI cancellation sends PACS.002 with RJCT status
- UI cancellation generates and sends PACS.004
- camt.056 cancellation remains unchanged (CANC status, no PACS.004)

**Key Distinction**:
- **CANC**: Payment cancelled before processing (instructing bank request)
- **RJCT + PACS.004**: Payment rejected/cancelled after processing attempt (business reasons, funds need return)

