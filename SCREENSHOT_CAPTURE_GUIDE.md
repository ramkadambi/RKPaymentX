# Screenshot Capture Guide for BA and Testers

This guide will help you capture all necessary screenshots for the training materials without requiring Kafka.

## Prerequisites

- Java 8+ installed
- Maven installed
- No Kafka required (mock mode enabled)

---

## Step 1: Start the Application

### Option A: Using PowerShell Script (Recommended)
```powershell
cd payment-orchestrator
.\start-for-screenshots.ps1
```

### Option B: Manual Start
```bash
cd payment-orchestrator
mvn spring-boot:run
```

**Wait for**: "Started PaymentOrchestratorApplication" message in console

---

## Step 2: Access the Dashboard

1. Open your web browser (Chrome recommended for best screenshots)
2. Navigate to: **`http://localhost:8081/index.html`**
3. You should see the empty dashboard

> **📸 Screenshot 1**: Empty dashboard with header, summary cards showing zeros, empty filters, and empty table

---

## Step 3: Load Mock Data

1. Click the **"Load Mock Data"** button in the header
2. Wait for success message: "Mock data loaded successfully! 5 errors available."
3. Click OK on the alert
4. The dashboard will automatically refresh

> **📸 Screenshot 2**: Dashboard after loading mock data - showing summary cards with numbers, error table populated

---

## Step 4: Capture Dashboard Overview

### Screenshot 3: Summary Cards
- Zoom in or crop to show the 4 summary cards clearly
- Should show: Total Errors (5), Today (5), High Priority (2), New (5)

### Screenshot 4: Filter Panel
- Show the filter dropdowns and search box
- Include the "Apply Filters" button

### Screenshot 5: Error Table - All Errors
- Show the full error table with all 5 errors
- Should show different severities (MEDIUM, HIGH, CRITICAL) and all NEW status

---

## Step 5: Capture Filtering Scenarios

### Screenshot 6: Filter by Service
1. Select "Account Validation" from Service Filter
2. Click "Apply Filters"
3. Capture the filtered table showing only Account Validation errors

### Screenshot 7: Filter by Status
1. Reset filters (select "All Services")
2. Select "New" from Status Filter
3. Click "Apply Filters"
4. Capture the filtered results

### Screenshot 8: Search by E2E ID
1. Type "INV-SANCTIONS-001" in the search box
2. Press Enter or click "Apply Filters"
3. Capture the table showing only that one error

---

## Step 6: Capture Error Detail Views

### Screenshot 9: Error Detail - Account Validation
1. Find error with E2E ID: **INV-ACC-VAL-001**
2. Click **"View"** button
3. Capture the full error detail modal showing:
   - Payment Information section
   - Error Information section
   - Processing History timeline
   - Error Context section
   - Action buttons (Fix & Resume, Restart, Cancel)

### Screenshot 10: Error Detail - Sanctions Check
1. Close the previous modal (click X or outside)
2. Find error with E2E ID: **INV-SANCTIONS-001**
3. Click **"View"** button
4. Capture showing CRITICAL severity and different error details

### Screenshot 11: Error Detail - Balance Check
1. Close the previous modal
2. Find error with E2E ID: **INV-BALANCE-001**
2. Click **"View"** button
3. Capture showing HIGH severity

### Screenshot 12: Processing History Timeline
- Zoom in on the Processing History section in any error detail
- Should show: PASSED steps (green), FAILED step (red), NOT STARTED steps (gray)

---

## Step 7: Capture Fix & Resume Flow

### Screenshot 13: Fix & Resume Modal - Empty
1. Open error detail for **INV-ACC-VAL-001** (Account Validation error)
2. Click **"Fix & Resume"** button (blue)
3. Capture the empty modal with all fields visible

### Screenshot 14: Fix & Resume Modal - Filled
1. In the same modal:
   - Select "Data Correction" from Fix Type dropdown
   - Enter in Fix Details: "Corrected creditor account number from 123456 to 654321"
   - Enter in Comments: "Customer provided correct account details via phone call"
2. Capture the filled form (don't submit yet)

### Screenshot 15: Success Message
1. Click **"Fix & Resume"** button to confirm
2. Capture the success alert: "Payment fixed and resumed successfully!"
3. Click OK

### Screenshot 16: Updated Status
1. After the modal closes, find the same error in the table
2. Capture showing the status changed to **"FIXED"** (green badge)
3. Note: You may need to refresh or the error may have moved

---

## Step 8: Capture Cancel & Return Flow

### Screenshot 17: Cancel Modal - Empty
1. Open error detail for **INV-SANCTIONS-001** (Sanctions Check error)
2. Click **"Cancel & Return"** button (red)
3. Capture the empty cancel modal

### Screenshot 18: Cancel Modal - Filled
1. In the same modal:
   - Select "Sanctions Hit" from Cancellation Reason dropdown
   - Enter in Comments: "OFAC sanctions match detected. Payment blocked per regulatory requirements."
2. Capture the filled form (don't submit yet)

### Screenshot 19: Cancel Success Message
1. Click **"Cancel & Return"** button to confirm
2. Capture the success alert: "Payment cancelled and returned!"
3. Click OK

### Screenshot 20: Cancelled Status
1. After the modal closes, find the same error in the table
2. Capture showing the status changed to **"CANCELLED"** (gray badge)

---

## Step 9: Capture Restart Flow

### Screenshot 21: Restart Modal
1. Reload mock data (click "Load Mock Data" again) to get fresh errors
2. Open error detail for **INV-BALANCE-001** (Balance Check error)
3. Click **"Restart from Beginning"** button
4. Capture the restart modal with warning message
5. Enter in Comments: "Customer will deposit funds. Restarting to re-check balance."
6. Capture the filled form

### Screenshot 22: Restart Success
1. Click **"Restart"** button
2. Capture the success message
3. Note the status change

---

## Step 10: Capture Status Variations

### Screenshot 23: Multiple Status Badges
After performing various actions, capture a table showing:
- NEW status (blue badge)
- FIXED status (green badge)
- CANCELLED status (gray badge)
- IN_PROGRESS status (orange badge) - if available

### Screenshot 24: Severity Badges Reference
Capture a table or section showing all severity badges:
- LOW (gray)
- MEDIUM (orange)
- HIGH (dark orange)
- CRITICAL (red)

---

## Step 11: Capture Edge Cases

### Screenshot 25: Empty State
1. Clear all errors (or wait for them to be processed)
2. Capture the "No errors found" empty state message

### Screenshot 26: Error Loading
1. If possible, capture any error messages or loading states

---

## Screenshot Organization

Organize your screenshots with these names:

```
screenshots/
├── 01-empty-dashboard.png
├── 02-dashboard-with-data.png
├── 03-summary-cards.png
├── 04-filter-panel.png
├── 05-error-table-all.png
├── 06-filter-by-service.png
├── 07-filter-by-status.png
├── 08-search-e2e-id.png
├── 09-error-detail-account-validation.png
├── 10-error-detail-sanctions.png
├── 11-error-detail-balance.png
├── 12-processing-history-timeline.png
├── 13-fix-modal-empty.png
├── 14-fix-modal-filled.png
├── 15-fix-success.png
├── 16-status-fixed.png
├── 17-cancel-modal-empty.png
├── 18-cancel-modal-filled.png
├── 19-cancel-success.png
├── 20-status-cancelled.png
├── 21-restart-modal.png
├── 22-restart-success.png
├── 23-multiple-status-badges.png
├── 24-severity-badges.png
├── 25-empty-state.png
└── 26-error-loading.png
```

---

## Tips for Better Screenshots

1. **Browser Settings**:
   - Use Chrome or Edge for best rendering
   - Set browser zoom to 100%
   - Use full-screen mode (F11) for cleaner shots

2. **Screenshot Tools**:
   - Windows: Snipping Tool or Win+Shift+S
   - Browser: Extensions like "Full Page Screen Capture"
   - Professional: Snagit, Greenshot, or Lightshot

3. **Image Quality**:
   - Use PNG format for best quality
   - Minimum resolution: 1920x1080
   - Crop unnecessary browser chrome
   - Add annotations if needed (arrows, highlights)

4. **Consistency**:
   - Use same browser for all screenshots
   - Keep window size consistent
   - Use same zoom level

5. **Annotations** (Optional):
   - Add red arrows pointing to important buttons
   - Highlight key fields with boxes
   - Add numbered steps if creating a flow diagram

---

## Quick Reference: Mock Data Errors

| E2E ID | Service | Severity | Status | Best Action |
|--------|---------|----------|--------|-------------|
| INV-ACC-VAL-001 | Account Validation | MEDIUM | NEW | Fix & Resume |
| INV-SANCTIONS-001 | Sanctions Check | CRITICAL | NEW | Cancel & Return |
| INV-BALANCE-001 | Balance Check | HIGH | NEW | Restart |
| INV-POSTING-001 | Payment Posting | MEDIUM | NEW | Fix & Resume |
| INV-ROUTING-001 | Routing Validation | HIGH | NEW | Cancel & Return |

---

## Troubleshooting

**Issue**: Application won't start
- **Solution**: Check if port 8081 is available. Change port in `application.properties` if needed.

**Issue**: Mock data won't load
- **Solution**: Check browser console (F12) for errors. Verify application is running.

**Issue**: Actions don't work
- **Solution**: Make sure you're in mock mode (`error.management.mock.mode=true` in application.properties)

**Issue**: Screenshots look blurry
- **Solution**: Use browser zoom at 100%, take full-resolution screenshots, save as PNG.

---

## Next Steps After Capturing

1. Review all screenshots for clarity
2. Insert into `OPERATOR_TRAINING_GUIDE.md` replacing placeholders
3. Create a PowerPoint/PDF presentation if needed
4. Share with BA and testers for review

---

**Happy Screenshot Capturing! 📸**

