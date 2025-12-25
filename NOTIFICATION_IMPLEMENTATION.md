# PACS.002 Notification Implementation

## Overview

This document describes the implementation of PACS.002 status notifications for the payment processing engine. The system now sends status updates to the instructing bank at each step of the payment processing journey.

## Components Created

### 1. Pacs002Status Enum (`payment-common`)
- **Location**: `payment-common/src/main/java/com/wellsfargo/payment/canonical/enums/Pacs002Status.java`
- **Purpose**: Defines all PACS.002 status codes
- **Status Codes**:
  - `RCVD` - Payment received
  - `ACCP` - Accepted for processing (basic validation passed)
  - `ACCC` - Accepted after customer profile validation
  - `PDNG` - Pending further checks (sanctions, balance)
  - `ACSP` - Accepted, settlement in process
  - `ACSC` - Accepted, settlement completed
  - `RJCT` - Rejected
  - `CANC` - Cancelled (after camt.056 request)

### 2. Pacs002Generator (`payment-common`)
- **Location**: `payment-common/src/main/java/com/wellsfargo/payment/notification/Pacs002Generator.java`
- **Purpose**: Generates ISO 20022 compliant PACS.002 XML messages
- **Features**:
  - Creates properly formatted XML with all required fields
  - Maps PaymentEvent data to PACS.002 structure
  - Includes original message information
  - Provides status reason codes and additional information

### 3. NotificationService (`payment-common`)
- **Location**: `payment-common/src/main/java/com/wellsfargo/payment/notification/NotificationService.java`
- **Purpose**: Publishes PACS.002 status reports to Kafka notification topic
- **Topic**: `payments.notification`
- **Features**:
  - Kafka producer for publishing status updates
  - Integrates with Pacs002Generator
  - Handles errors gracefully

### 4. CancellationHandler (`payment-orchestrator`)
- **Location**: `payment-orchestrator/src/main/java/com/wellsfargo/payment/orchestrator/CancellationHandler.java`
- **Purpose**: Processes camt.056 cancellation requests
- **Topic**: `payments.cancellation.in`
- **Features**:
  - Consumes camt.056 cancellation requests
  - Validates if payment can be cancelled
  - Sends CANC status via PACS.002
  - Prevents further processing of cancelled payments

## Status Flow

The payment processing flow now sends status notifications at each step:

1. **RCVD** - When payment is received from ingress
   - Triggered: `PaymentOrchestratorService.handleIngressPayment()`
   - Message: "Payment received and acknowledged"

2. **ACCP** - After account validation passes
   - Triggered: `PaymentOrchestratorService.handleServiceResult()` (account_validation)
   - Message: "Payment accepted for processing"

3. **ACCC** - After routing validation passes
   - Triggered: `PaymentOrchestratorService.handleServiceResult()` (routing_validation)
   - Message: "Payment accepted after customer profile validation"

4. **PDNG** - After sanctions check passes (before balance check)
   - Triggered: `PaymentOrchestratorService.handleServiceResult()` (sanctions_check)
   - Message: "Payment pending balance verification"

5. **ACSP** - After balance check passes (settlement in process)
   - Triggered: `PaymentOrchestratorService.handleServiceResult()` (balance_check)
   - Message: "Payment accepted, settlement in process"

6. **ACSC** - After payment posting completes
   - Triggered: `PaymentOrchestratorService.handleServiceResult()` (payment_posting)
   - Message: "Payment accepted, settlement completed"

7. **RJCT** - When any step fails
   - Triggered: `PaymentOrchestratorService.handleServiceResult()` (any service FAIL/ERROR)
   - Message: "Payment rejected at {service} stage"

8. **CANC** - When cancellation request is processed
   - Triggered: `CancellationHandler.handleCancellationRequest()`
   - Message: "Payment cancelled as requested"

## Integration Points

### PaymentOrchestratorService Updates

The orchestrator service has been updated to:
- Initialize `NotificationService` on startup
- Send status notifications at each processing step
- Handle rejection notifications
- Integrate with `CancellationHandler`

### Cancellation Flow

1. Ingress service receives camt.056 cancellation request
2. Publishes to `payments.cancellation.in` topic
3. `CancellationHandler` consumes and processes
4. Validates payment can be cancelled (not yet settled)
5. Removes payment from orchestrator cache
6. Sends CANC status via PACS.002

## Kafka Topics

### New Topics

1. **payments.notification**
   - Type: Producer (NotificationService)
   - Content: PACS.002 XML messages
   - Key: `endToEndId`
   - Purpose: Status updates to instructing bank

2. **payments.cancellation.in**
   - Type: Consumer (CancellationHandler)
   - Content: camt.056 XML messages
   - Key: `endToEndId`
   - Purpose: Cancellation requests from instructing bank

## PACS.002 Message Structure

Generated PACS.002 messages include:

```xml
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10">
  <FIToFIPmtStsRpt>
    <GrpHdr>
      <MsgId>WF-STATUS-{timestamp}-{endToEndId}</MsgId>
      <CreDtTm>{current timestamp}</CreDtTm>
    </GrpHdr>
    <OrgnlGrpInfAndSts>
      <OrgnlMsgId>{original message ID}</OrgnlMsgId>
      <OrgnlMsgNmId>{pacs.008.001.08 or pacs.009.001.08}</OrgnlMsgNmId>
      <OrgnlNbOfTxs>1</OrgnlNbOfTxs>
      <OrgnlCtrlSum>{original amount}</OrgnlCtrlSum>
      <GrpSts>{status code}</GrpSts>
    </OrgnlGrpInfAndSts>
    <OrgnlPmtInfAndSts>
      <TxInfAndSts>
        <OrgnlInstrId>{original instruction ID}</OrgnlInstrId>
        <OrgnlEndToEndId>{endToEndId}</OrgnlEndToEndId>
        <TxSts>{status code}</TxSts>
        <StsRsnInf>
          <Rsn>
            <Cd>{reason code}</Cd>
          </Rsn>
          <AddtlInf>{additional information}</AddtlInf>
        </StsRsnInf>
      </TxInfAndSts>
    </OrgnlPmtInfAndSts>
  </FIToFIPmtStsRpt>
</Document>
```

## Testing

### Test Files

Sample test files are available:
- `test-data/HDFC2WELLSpacs.008.xml` - Sample PACS.008 payment
- `test-data/HDFC2WELLSpacs.009.xml` - Sample PACS.009 payment
- `test-data/WELLS2HDFC.ACSP.pacs.002.xml` - Sample PACS.002 status
- `test-data/HDFC2WELLScamt.056.xml` - Sample camt.056 cancellation

### Testing Status Notifications

1. Process a payment through the system
2. Monitor `payments.notification` topic
3. Verify PACS.002 messages are published at each step
4. Verify status codes match the processing stage

### Testing Cancellation

1. Send camt.056 cancellation request to `payments.cancellation.in`
2. Verify payment is removed from processing
3. Verify CANC status is sent via PACS.002
4. Verify payment does not proceed to next steps

## Configuration

### Kafka Configuration

Both services use standard Kafka configuration:
- `kafka.bootstrap.servers` (default: `localhost:9092`)
- `kafka.consumer.group-id` (default: `cancellation-handler-group` for CancellationHandler)
- `kafka.consumer.auto-offset-reset` (default: `earliest`)

### Topic Creation

Create the notification topic:
```bash
kafka-topics.sh --create \
  --bootstrap-server localhost:9092 \
  --topic payments.notification \
  --partitions 20 \
  --replication-factor 1
```

Create the cancellation topic:
```bash
kafka-topics.sh --create \
  --bootstrap-server localhost:9092 \
  --topic payments.cancellation.in \
  --partitions 20 \
  --replication-factor 1
```

## Future Enhancements

1. **Egress Integration**: Egress services should consume from `payments.notification` and deliver PACS.002 messages via SWIFT
2. **Status Persistence**: Store status history for audit and reconciliation
3. **Retry Logic**: Implement retry logic for failed status notifications
4. **Status Query**: Add ability to query current status of a payment
5. **Webhook Support**: Add webhook support for real-time status updates

## Notes

- Status notifications are sent asynchronously via Kafka
- Each status update is idempotent (can be safely retried)
- Cancellation is only possible before settlement is completed
- All PACS.002 messages are ISO 20022 compliant
- Status codes follow SWIFT standards

