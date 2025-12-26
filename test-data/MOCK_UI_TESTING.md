# Mock UI Testing Guide

## Overview

The Error Management UI can be tested without Kafka by using mock data mode. This allows you to:
- View sample error records
- Test Fix & Resume functionality
- Test Restart from Beginning functionality
- Test Cancel & Return functionality (with PACS.004 generation)

## Setup

1. **Enable Mock Mode**

   Edit `payment-orchestrator/src/main/resources/application.properties`:
   ```properties
   error.management.mock.mode=true
   ```

2. **Start the Orchestrator Service**

   ```bash
   cd payment-orchestrator
   mvn spring-boot:run
   ```

   The service will start on `http://localhost:8081`

3. **Access the UI**

   Open your browser and navigate to:
   ```
   http://localhost:8081
   ```

## Loading Mock Data

1. Click the **"Load Mock Data"** button in the header
2. You should see a success message: "Mock data loaded successfully! 5 errors available."
3. The dashboard will automatically refresh and display the sample errors

## Sample Errors Included

The mock data includes 5 different error scenarios:

### 1. Account Validation Error (Fixable)
- **E2E ID**: `INV-ACC-VAL-001`
- **Service**: `account_validation`
- **Error**: Account number format invalid
- **Action**: Can be **Fixed & Resumed**

### 2. Sanctions Check Error (Should Cancel)
- **E2E ID**: `INV-SANCTIONS-001`
- **Service**: `sanctions_check`
- **Error**: Sanctions match found in OFAC list
- **Action**: Should be **Cancelled & Returned** (generates PACS.004 with RR01 reason code)

### 3. Balance Check Error (Restart)
- **E2E ID**: `INV-BALANCE-001`
- **Service**: `balance_check`
- **Error**: Insufficient funds
- **Action**: Can be **Restarted from Beginning**

### 4. Payment Posting Error (Fixable)
- **E2E ID**: `INV-POSTING-001`
- **Service**: `payment_posting`
- **Error**: Database connection timeout
- **Action**: Can be **Fixed & Resumed**

### 5. Routing Validation Error (Should Cancel)
- **E2E ID**: `INV-ROUTING-001`
- **Service**: `routing_validation`
- **Error**: Invalid BIC code
- **Action**: Should be **Cancelled & Returned** (generates PACS.004 with RC01 reason code)

## Testing Actions

### Fix & Resume
1. Click **"View"** on an error (e.g., Account Validation or Payment Posting)
2. Click **"Fix & Resume"**
3. Enter fix details and comments
4. Click **"Confirm"**
5. The error status will change to **FIXED**

### Restart from Beginning
1. Click **"View"** on an error (e.g., Balance Check)
2. Click **"Restart"**
3. Enter comments
4. Click **"Confirm"**
5. The error status will change to **RESOLVED**

### Cancel & Return (PACS.004)
1. Click **"View"** on an error that should be cancelled (e.g., Sanctions Check or Routing Validation)
2. Click **"Cancel"**
3. Select a cancellation reason:
   - `sanctions` → Generates PACS.004 with **RR01** (Regulatory reason)
   - `invalid_bank_identifier` → Generates PACS.004 with **RC01** (Invalid bank identifier)
   - `business_rule` → Generates PACS.004 with **AG01** (Transaction forbidden)
   - Or any other reason → Generates PACS.004 with **NARR** (Narrative reason)
4. Enter comments
5. Click **"Confirm"**
6. The error status will change to **CANCELLED**
7. A PACS.004 message will be generated (in mock mode, it's logged but not published to Kafka)

## Mock Mode Behavior

In mock mode:
- **No Kafka connections** are established
- **No messages** are published to Kafka topics
- **PACS.002 and PACS.004 messages** are generated but only logged (not published)
- **Error status updates** work normally
- **All UI functionality** works as expected

## Viewing Generated Messages

In mock mode, PACS.002 and PACS.004 messages are generated but not published. To see them:
1. Check the application logs for messages like:
   ```
   Published PACS.002 status - E2E=..., status=...
   Published PACS.004 return - E2E=..., reasonCode=...
   ```

## Disabling Mock Mode

To use real Kafka integration:
1. Edit `application.properties`:
   ```properties
   error.management.mock.mode=false
   ```
2. Ensure Kafka is running on `localhost:9092`
3. Restart the service

## Troubleshooting

- **No errors showing**: Click "Load Mock Data" button
- **Actions not working**: Check browser console for errors
- **Service not starting**: Check that port 8081 is available
- **Mock data not loading**: Check application logs for errors

