Write-Host "========================================" -ForegroundColor Cyan
Write-Host " Restarting Payment Orchestrator" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Find and stop existing orchestrator processes
Write-Host "Step 1: Checking for running orchestrator processes..." -ForegroundColor Yellow

$javaProcesses = Get-Process -Name "java" -ErrorAction SilentlyContinue
$orchestratorFound = $false

foreach ($proc in $javaProcesses) {
    try {
        $cmdLine = (Get-CimInstance Win32_Process -Filter "ProcessId = $($proc.Id)").CommandLine
        if ($cmdLine -and ($cmdLine -match "PaymentOrchestratorApplication" -or $cmdLine -match "payment-orchestrator")) {
            Write-Host "  Found orchestrator process: PID $($proc.Id)" -ForegroundColor Cyan
            Write-Host "  Stopping process..." -ForegroundColor Yellow
            Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
            Start-Sleep -Seconds 2
            $orchestratorFound = $true
        }
    } catch {
        # Ignore errors when checking command line
    }
}

if (-not $orchestratorFound) {
    Write-Host "  No orchestrator process found running" -ForegroundColor Gray
}

Write-Host ""

# Build the orchestrator
Write-Host "Step 2: Building orchestrator..." -ForegroundColor Yellow
Push-Location "..\payment-orchestrator"

try {
    mvn clean package -DskipTests -q 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  [OK] Build successful" -ForegroundColor Green
    } else {
        Write-Host "  [FAILED] Build failed" -ForegroundColor Red
        Pop-Location
        exit 1
    }
} catch {
    Write-Host "  [FAILED] Build error: $_" -ForegroundColor Red
    Pop-Location
    exit 1
}

Pop-Location

Write-Host ""

# Start the orchestrator
Write-Host "Step 3: Starting orchestrator..." -ForegroundColor Yellow

$orchestratorDir = (Resolve-Path "..\payment-orchestrator").Path
$logDir = $orchestratorDir
$stdOut = Join-Path $logDir "orchestrator.log"
$stdErr = Join-Path $logDir "orchestrator-error.log"

# Use Maven exec:java to run with proper classpath
Write-Host "  Starting orchestrator via Maven exec:java..." -ForegroundColor Gray

# Start orchestrator using mvn exec:java in background
$orchestratorJob = Start-Process -FilePath "mvn" `
    -ArgumentList "exec:java", "-Dexec.mainClass=com.wellsfargo.payment.orchestrator.PaymentOrchestratorApplication" `
    -WorkingDirectory $orchestratorDir `
    -NoNewWindow `
    -PassThru `
    -RedirectStandardOutput $stdOut `
    -RedirectStandardError $stdErr

Write-Host "  [OK] Orchestrator started (PID: $($orchestratorJob.Id))" -ForegroundColor Green
Write-Host ""

# Wait a bit for startup
Write-Host "Step 4: Waiting for orchestrator to initialize..." -ForegroundColor Yellow
Start-Sleep -Seconds 5

# Check if process is still running
$stillRunning = Get-Process -Id $orchestratorJob.Id -ErrorAction SilentlyContinue
if ($stillRunning) {
    Write-Host "  [OK] Orchestrator is running" -ForegroundColor Green
} else {
    Write-Host "  [WARNING] Orchestrator process may have exited" -ForegroundColor Yellow
    Write-Host "  Check logs: ..\payment-orchestrator\orchestrator-error.log" -ForegroundColor Gray
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host " Orchestrator Restart Complete" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Next steps:" -ForegroundColor Cyan
Write-Host "  1. Monitor logs: ..\payment-orchestrator\orchestrator.log" -ForegroundColor Gray
Write-Host "  2. Check Kafka consumer groups to verify it's consuming" -ForegroundColor Gray
Write-Host "  3. Re-run payment flow check to see if final status is published" -ForegroundColor Gray
Write-Host ""

