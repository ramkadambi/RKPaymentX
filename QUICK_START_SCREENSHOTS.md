# Quick Start Guide - Taking Screenshots

## 🚀 Fast Setup (3 Steps)

### Step 1: Start the Application
```powershell
cd payment-orchestrator
.\start-for-screenshots.ps1
```

**OR manually:**
```bash
cd payment-orchestrator
mvn spring-boot:run
```

Wait for: `Started PaymentOrchestratorApplication` message

---

### Step 2: Open the Dashboard
1. Open browser: **http://localhost:8081/index.html**
2. Click **"Load Mock Data"** button
3. Wait for success message

---

### Step 3: Take Screenshots
Follow the detailed guide: **SCREENSHOT_CAPTURE_GUIDE.md**

---

## 📋 Quick Checklist

- [ ] Application started (port 8081)
- [ ] Dashboard loaded in browser
- [ ] Mock data loaded (5 errors visible)
- [ ] Screenshot tool ready (Snipping Tool, etc.)

---

## 🎯 Essential Screenshots (Minimum Set)

1. **Dashboard with data** - Shows all errors
2. **Error detail modal** - Shows full error information
3. **Fix & Resume modal** - Shows repair form
4. **Cancel modal** - Shows cancellation form
5. **Status badges** - Shows different statuses (NEW, FIXED, CANCELLED)

---

## ⚡ Quick Tips

- **Browser**: Use Chrome for best rendering
- **Zoom**: Set to 100% for consistent screenshots
- **Format**: Save as PNG for best quality
- **Naming**: Use descriptive names (e.g., `01-dashboard.png`)

---

## 🆘 Troubleshooting

**Port 8081 in use?**
- Change port in `application.properties`: `server.port=8082`
- Update URL to: `http://localhost:8082/index.html`

**Mock data not loading?**
- Check browser console (F12) for errors
- Verify application is running (check terminal)
- Try refreshing the page

**Application won't start?**
- Check Java is installed: `java -version`
- Check Maven is installed: `mvn -version`
- Check port is available

---

**For detailed instructions, see: SCREENSHOT_CAPTURE_GUIDE.md**

