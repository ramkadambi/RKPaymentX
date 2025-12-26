# Payment Error Management - Quick Reference Card

## Access
**URL**: `http://localhost:8080/index.html`

---

## Dashboard Layout

```
┌─────────────────────────────────────────┐
│  Header: Title + Refresh Button        │
├─────────────────────────────────────────┤
│  Summary Cards: Total | Today | High | New │
├─────────────────────────────────────────┤
│  Filters: Service | Status | Search     │
├─────────────────────────────────────────┤
│  Error Table: Time | E2E ID | Step |   │
│              Error | Severity | Status │
└─────────────────────────────────────────┘
```

---

## How to Repair a Payment

### Quick Steps
1. **Find Error** → Click "View" button
2. **Review Details** → Check error information
3. **Click "Fix & Resume"** → Blue button
4. **Fill Form**:
   - Fix Type: Data Correction / Override / Manual Fix
   - Fix Details: Describe what was fixed
   - Comments: Optional notes
5. **Click "Fix & Resume"** → Confirm
6. **Verify** → Check status updated to "FIXED"

### When to Use
✅ Data errors that can be corrected  
✅ Business rule overrides needed  
✅ Payment should continue from failed step

---

## How to Cancel a Payment

### Quick Steps
1. **Find Error** → Click "View" button
2. **Review Details** → Confirm cancellation needed
3. **Click "Cancel & Return"** → Red button
4. **Fill Form**:
   - Cancellation Reason: Select from dropdown
   - Comments: Explain why (recommended)
5. **Click "Cancel & Return"** → Confirm
6. **Verify** → Check status updated to "CANCELLED"

### When to Use
❌ Payment cannot be processed  
❌ Sanctions hit  
❌ Invalid account  
❌ Customer requested cancellation  
❌ Insufficient funds (cannot resolve)

---

## Status Badges

### Severity
- 🔵 **LOW** - Minor issue
- 🟡 **MEDIUM** - Normal priority
- 🟠 **HIGH** - High priority
- 🔴 **CRITICAL** - Immediate attention

### Status
- 🔵 **NEW** - Not yet addressed
- 🟡 **IN_PROGRESS** - Being worked on
- 🟢 **FIXED** - Error resolved
- ⚫ **CANCELLED** - Payment cancelled

---

## Filters

**Service Filter**: Account Validation | Routing | Sanctions | Balance | Posting  
**Status Filter**: New | In Progress | Fixed | Cancelled  
**Search**: Type E2E ID and press Enter

---

## Keyboard Shortcuts

- **Enter** (in search): Apply filter
- **Escape**: Close modal

---

## Common Actions

| Action | Button Color | When to Use |
|--------|-------------|-------------|
| Fix & Resume | 🔵 Blue | Correct error, continue processing |
| Restart | 🔵 Blue | Start payment from beginning |
| Cancel & Return | 🔴 Red | Stop payment, return funds |

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| No errors shown | Normal if no errors exist. Use "Load Mock Data" for training |
| Can't see action buttons | Error may be fixed/cancelled. Check status badge |
| Action fails | Check required fields filled, verify payment exists |
| Filters not working | Click "Apply Filters" after selecting options |

---

## Important Reminders

⚠️ **Always review error details before taking action**  
📝 **Document actions in comments field**  
✅ **Verify status after action**  
🔄 **Dashboard auto-refreshes every 30 seconds**  
❌ **Cancellation is permanent - funds will be returned**

---

**For detailed instructions, see: OPERATOR_TRAINING_GUIDE.md**
image.png
