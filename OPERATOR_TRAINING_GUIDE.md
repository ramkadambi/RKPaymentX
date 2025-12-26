# Payment Error Management System - Operator Training Guide

## Table of Contents
1. [Introduction](#introduction)
2. [Accessing the System](#accessing-the-system)
3. [Dashboard Overview](#dashboard-overview)
4. [Navigating the Interface](#navigating-the-interface)
5. [How to Repair a Payment (Fix & Resume)](#how-to-repair-a-payment-fix--resume)
6. [How to Cancel a Payment](#how-to-cancel-a-payment)
7. [Additional Features](#additional-features)
8. [Troubleshooting](#troubleshooting)

---

## Introduction

The Payment Error Management Dashboard is a web-based interface that allows operators to monitor, investigate, and resolve payment processing errors. This guide will walk you through the essential operations you'll perform daily.

### Key Features
- **Real-time Error Monitoring**: View all payment errors in one place
- **Error Investigation**: Detailed view of payment and error information
- **Payment Repair**: Fix errors and resume payment processing
- **Payment Cancellation**: Cancel payments and return funds when necessary
- **Filtering & Search**: Quickly find specific errors

---

## Accessing the System

### Step 1: Start the Application
1. Ensure the Payment Orchestrator service is running
2. Open your web browser (Chrome, Firefox, Edge, or Safari)
3. Navigate to: `http://localhost:8080/index.html`

> **📸 Screenshot Placeholder 1**: Browser showing the login/landing page or dashboard URL

### Step 2: Verify Access
- You should see the "Payment Error Management Dashboard" header
- The dashboard should display summary cards at the top
- If you see an error, contact your system administrator

---

## Dashboard Overview

The dashboard consists of four main sections:

### 1. Header Section
- **Title**: "Payment Error Management Dashboard"
- **Actions**:
  - **Load Mock Data** button (for testing)
  - **Refresh** button (manually refresh the error list)

> **📸 Screenshot Placeholder 2**: Full dashboard view showing header, summary cards, filters, and error table

### 2. Summary Cards
Four cards display key metrics:
- **Total Errors**: Total number of errors in the system
- **Today**: Errors that occurred today
- **High Priority**: Errors with HIGH or CRITICAL severity
- **New**: Errors with NEW status

> **📸 Screenshot Placeholder 3**: Close-up of the four summary cards

### 3. Filter Panel
Located below the summary cards, allows you to:
- **Service Filter**: Filter by service (Account Validation, Routing Validation, Sanctions Check, Balance Check, Payment Posting)
- **Status Filter**: Filter by status (New, In Progress, Fixed, Cancelled)
- **Search Box**: Search by End-to-End ID (E2E ID)
- **Apply Filters** button: Apply your filter selections

> **📸 Screenshot Placeholder 4**: Filter panel with dropdowns and search box

### 4. Error List Table
A table displaying all errors with the following columns:
- **Time**: When the error occurred
- **E2E ID**: End-to-End ID of the payment
- **Step**: Which processing step failed
- **Error**: Error message/reason
- **Severity**: Badge showing severity level (LOW, MEDIUM, HIGH, CRITICAL)
- **Status**: Badge showing current status (NEW, IN_PROGRESS, FIXED, CANCELLED)
- **Actions**: "View" button to see error details

> **📸 Screenshot Placeholder 5**: Error table with sample errors showing different severities and statuses

---

## Navigating the Interface

### Viewing Error Details

1. **Locate the Error**: Use filters or scroll through the error table
2. **Click "View"**: Click the blue "View" button in the Actions column for the error you want to investigate
3. **Review Details**: A modal window will open showing:
   - **Payment Information**: E2E ID, amount, currency, debtor, creditor, original message type
   - **Error Information**: Failed step, error type, error code, error message, timestamp, last successful step
   - **Processing History**: Visual timeline showing which steps passed, failed, or haven't started
   - **Error Context**: Service name, error topic, severity

> **📸 Screenshot Placeholder 6**: Error detail modal showing all sections

### Using Filters

**Example: Find all Account Validation errors**
1. In the "Service Filter" dropdown, select "Account Validation"
2. Click "Apply Filters"
3. The table will update to show only Account Validation errors

**Example: Find a specific payment**
1. Type the E2E ID in the search box (e.g., "E2E123456789")
2. Press Enter or click "Apply Filters"
3. The table will show only matching payments

**Example: Find all new high-priority errors**
1. Set "Status Filter" to "New"
2. Set "Service Filter" to the service you're interested in
3. Click "Apply Filters"
4. Review the results and look for HIGH or CRITICAL severity badges

> **📸 Screenshot Placeholder 7**: Filtered results showing specific errors

### Refreshing Data

- **Auto-refresh**: The dashboard automatically refreshes every 30 seconds
- **Manual refresh**: Click the "Refresh" button in the header to immediately update the data

---

## How to Repair a Payment (Fix & Resume)

The "Fix & Resume" feature allows you to correct an error and continue processing the payment from where it failed.

### When to Use Fix & Resume
- Data correction is needed (e.g., wrong account number, incorrect amount)
- Business rule override is required
- Manual intervention can resolve the issue
- The payment should continue from the failed step after fixing

### Step-by-Step Process

#### Step 1: Open Error Details
1. Find the error in the error table
2. Click the **"View"** button
3. Review the error details to understand what went wrong

> **📸 Screenshot Placeholder 8**: Error detail modal with "Fix & Resume" button visible

#### Step 2: Initiate Fix & Resume
1. In the error detail modal, click the **"Fix & Resume"** button (blue button)
2. The "Fix & Resume Payment" modal will open

> **📸 Screenshot Placeholder 9**: Fix & Resume modal with all fields visible

#### Step 3: Fill in Fix Details

**Fix Type** (Required):
- Select from the dropdown:
  - **Data Correction**: Use when correcting payment data (account numbers, amounts, etc.)
  - **Business Rule Override**: Use when overriding a business rule validation
  - **Manual Fix**: Use for other manual interventions

**Fix Details** (Required):
- Enter a detailed description of what was fixed
- Example: "Corrected creditor account number from 123456 to 654321"
- Example: "Overridden balance check - customer confirmed sufficient funds"

**Comments** (Optional):
- Add any additional notes or context
- Example: "Customer called to confirm account details"

> **📸 Screenshot Placeholder 10**: Fix & Resume modal with fields filled in (example data)

#### Step 4: Confirm the Fix
1. Review all entered information
2. Click the **"Fix & Resume"** button (blue button at bottom)
3. Wait for the confirmation message: "Payment fixed and resumed successfully!"
4. The modal will close automatically
5. The error list will refresh, and the error status should update

> **📸 Screenshot Placeholder 11**: Success message after fixing and resuming

#### Step 5: Verify the Fix
1. The error should disappear from the "New" errors or show status as "FIXED"
2. Check the summary cards - the error count should decrease
3. Monitor the payment to ensure it processes successfully

> **📸 Screenshot Placeholder 12**: Updated error table showing the fixed error with "FIXED" status badge

### Important Notes
- ⚠️ **Fix & Resume** continues processing from the failed step
- ✅ Use this when you've corrected the issue and want to continue
- ❌ Don't use this if you need to restart from the beginning (use "Restart from Beginning" instead)

---

## How to Cancel a Payment

The "Cancel & Return" feature allows you to cancel a payment and return funds to the sender.

### When to Use Cancel & Return
- Payment cannot be processed (e.g., sanctions hit, invalid account)
- Customer requested cancellation
- Business rule violation that cannot be overridden
- Insufficient funds that cannot be resolved
- Payment should be stopped and funds returned

### Step-by-Step Process

#### Step 1: Open Error Details
1. Find the error in the error table
2. Click the **"View"** button
3. Review the error details to confirm cancellation is appropriate

> **📸 Screenshot Placeholder 13**: Error detail modal with "Cancel & Return" button visible

#### Step 2: Initiate Cancellation
1. In the error detail modal, click the **"Cancel & Return"** button (red button)
2. The "Cancel & Return Payment" modal will open

> **📸 Screenshot Placeholder 14**: Cancel & Return modal with all fields visible

#### Step 3: Fill in Cancellation Details

**Cancellation Reason** (Required):
- Select from the dropdown:
  - **Business Rule Violation**: Payment violates business rules
  - **Sanctions Hit**: Payment blocked due to sanctions screening
  - **Insufficient Funds**: Account has insufficient funds
  - **Invalid Account**: Account number is invalid or closed
  - **Customer Request**: Customer requested cancellation
  - **Other**: Other reasons (specify in comments)

**Additional Comments** (Optional but Recommended):
- Provide detailed explanation for the cancellation
- Example: "Account closed per customer request on 2024-01-15"
- Example: "Sanctions hit - entity on OFAC SDN list"
- Example: "Insufficient funds - balance: $100, required: $500"

> **📸 Screenshot Placeholder 15**: Cancel & Return modal with fields filled in (example data)

#### Step 4: Confirm Cancellation
1. **⚠️ WARNING**: Review all information carefully - cancellation cannot be undone easily
2. Verify the cancellation reason is correct
3. Click the **"Cancel & Return"** button (red button at bottom)
4. Wait for the confirmation message: "Payment cancelled and returned!"
5. The modal will close automatically
6. The error list will refresh

> **📸 Screenshot Placeholder 16**: Success message after canceling payment

#### Step 5: Verify Cancellation
1. The error should show status as "CANCELLED" in the error table
2. Check the summary cards - cancelled errors are counted separately
3. The payment will generate a return message (PACS.004) to return funds
4. A rejection status (RJCT) will be sent to notify the sender

> **📸 Screenshot Placeholder 17**: Updated error table showing the cancelled error with "CANCELLED" status badge

### Important Notes
- ⚠️ **Cancellation is permanent** - the payment will not continue processing
- 💰 **Funds will be returned** to the sender via return message
- 📧 **Notifications will be sent** to inform relevant parties
- ✅ Use this when the payment cannot or should not proceed
- ❌ Don't use this if you can fix the issue (use "Fix & Resume" instead)

---

## Additional Features

### Restart from Beginning

Sometimes you may need to restart a payment from the very beginning (Account Validation step) rather than resuming from the failed step.

**When to Use:**
- When you need to re-process the entire payment flow
- When data corrections affect earlier processing steps
- When you want to clear all previous processing results

**How to Use:**
1. Open error details (click "View")
2. Click **"Restart from Beginning"** button
3. Review the warning message
4. Enter comments explaining why you're restarting
5. Click **"Restart"** to confirm

> **📸 Screenshot Placeholder 18**: Restart modal with warning message

### Understanding Status Badges

**Severity Badges:**
- 🔵 **LOW** (gray): Minor issues, low priority
- 🟡 **MEDIUM** (orange): Moderate issues, normal priority
- 🟠 **HIGH** (dark orange): Serious issues, high priority
- 🔴 **CRITICAL** (red): Critical issues, immediate attention required

**Status Badges:**
- 🔵 **NEW** (blue): New error, not yet addressed
- 🟡 **IN_PROGRESS** (orange): Error is being worked on
- 🟢 **FIXED** (green): Error has been fixed
- ⚫ **CANCELLED** (gray): Payment has been cancelled

> **📸 Screenshot Placeholder 19**: Table showing various badge colors and meanings

### Processing History Timeline

In the error detail modal, you'll see a "Processing History" section showing:
- ✅ **PASSED** (green): Step completed successfully
- ❌ **FAILED** (red): Step that failed
- ○ **NOT STARTED** (gray): Steps that haven't been reached yet

This helps you understand where the payment was in the processing flow when it failed.

> **📸 Screenshot Placeholder 20**: Processing history timeline showing passed, failed, and not started steps

---

## Troubleshooting

### Common Issues and Solutions

#### Issue: Dashboard shows "No errors found"
**Solution**: 
- This is normal if there are no errors
- Click "Load Mock Data" to see sample errors for training
- Check that the orchestrator service is running

#### Issue: Error details won't load
**Solution**:
- Check your internet connection
- Click "Refresh" button
- Verify the orchestrator service is running
- Check browser console for errors (F12)

#### Issue: "Fix & Resume" button is not visible
**Solution**:
- The error may already be fixed or cancelled
- Only NEW or IN_PROGRESS errors can be fixed
- Check the status badge in the error table

#### Issue: Action fails with an error message
**Solution**:
- Read the error message carefully
- Verify all required fields are filled
- Check that the payment event still exists
- Contact system administrator if issue persists

#### Issue: Filters not working
**Solution**:
- Make sure you click "Apply Filters" after selecting options
- Clear filters by selecting "All Services" and "All Status"
- Try refreshing the page

### Getting Help

If you encounter issues not covered here:
1. Check the browser console (F12) for error messages
2. Note the E2E ID of the problematic payment
3. Contact your system administrator with:
   - E2E ID
   - Error message
   - Steps you were performing
   - Screenshot if possible

---

## Quick Reference Card

### Keyboard Shortcuts
- **Enter** in search box: Apply search filter
- **Escape**: Close any open modal

### Common Workflows

**Fix a Data Error:**
1. Find error → View → Fix & Resume → Select "Data Correction" → Enter details → Confirm

**Cancel Invalid Payment:**
1. Find error → View → Cancel & Return → Select reason → Enter comments → Confirm

**Find Specific Payment:**
1. Type E2E ID in search box → Press Enter

**Filter by Service:**
1. Select service from dropdown → Click "Apply Filters"

---

## Best Practices

1. **Always review error details** before taking action
2. **Document your actions** in the comments field
3. **Verify the fix worked** by checking the error status after action
4. **Use appropriate cancellation reasons** for accurate reporting
5. **Don't cancel payments** that can be fixed
6. **Monitor high-priority errors** first
7. **Refresh regularly** to see latest errors (auto-refresh is every 30 seconds)

---

## Appendix: Screenshot Checklist

For complete training materials, capture screenshots of:

- [ ] Screenshot 1: Browser showing dashboard URL
- [ ] Screenshot 2: Full dashboard view
- [ ] Screenshot 3: Summary cards close-up
- [ ] Screenshot 4: Filter panel
- [ ] Screenshot 5: Error table with sample errors
- [ ] Screenshot 6: Error detail modal
- [ ] Screenshot 7: Filtered results
- [ ] Screenshot 8: Error detail with Fix & Resume button
- [ ] Screenshot 9: Fix & Resume modal (empty)
- [ ] Screenshot 10: Fix & Resume modal (filled)
- [ ] Screenshot 11: Success message after fix
- [ ] Screenshot 12: Updated table showing fixed error
- [ ] Screenshot 13: Error detail with Cancel button
- [ ] Screenshot 14: Cancel modal (empty)
- [ ] Screenshot 15: Cancel modal (filled)
- [ ] Screenshot 16: Success message after cancel
- [ ] Screenshot 17: Updated table showing cancelled error
- [ ] Screenshot 18: Restart modal
- [ ] Screenshot 19: Badge colors reference
- [ ] Screenshot 20: Processing history timeline

---

**Document Version**: 1.0  
**Last Updated**: 2024  
**For**: Payment Error Management System Operators

