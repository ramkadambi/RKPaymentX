# Reconciliation Test Runner
# Tests payment posting reconciliation across vostros, nostros, settlement accounts, and customer accounts

Write-Host "========================================" -ForegroundColor Cyan
Write-Host " Payment Reconciliation Test" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

Write-Host "This test will:" -ForegroundColor Yellow
Write-Host "  1. Load all settlement accounts with balances from test_ledger.json" -ForegroundColor Gray
Write-Host "  2. Execute test payments for different scenarios:" -ForegroundColor Gray
Write-Host "     - SWIFT Inbound to Wells Customer" -ForegroundColor Gray
Write-Host "     - SWIFT Inbound to External Bank" -ForegroundColor Gray
Write-Host "     - FED Inbound to External Bank" -ForegroundColor Gray
Write-Host "     - CHIPS Inbound to External Bank" -ForegroundColor Gray
Write-Host "     - Wells-Initiated Outbound via FED" -ForegroundColor Gray
Write-Host "     - Wells-Initiated Outbound via CHIPS" -ForegroundColor Gray
Write-Host "     - Wells-Initiated Outbound via SWIFT" -ForegroundColor Gray
Write-Host "  3. Validate reconciliation (debits and credits)" -ForegroundColor Gray
Write-Host "  4. Verify account balances are correct" -ForegroundColor Gray
Write-Host ""

$projectRoot = Split-Path -Parent $PSScriptRoot
$satellitesModule = Join-Path $projectRoot "payment-satellites"

Write-Host "Running reconciliation tests..." -ForegroundColor Green
Write-Host ""

# Compile and run the standalone test
Set-Location $satellitesModule
Write-Host "Compiling..." -ForegroundColor Yellow
mvn compile -q

if ($LASTEXITCODE -eq 0) {
    Write-Host "Running reconciliation tests..." -ForegroundColor Yellow
    Write-Host ""
    
    # Run the standalone test runner
    $testClass = "com.wellsfargo.payment.satellites.paymentposting.ReconciliationTestRunner"
    mvn exec:java "-Dexec.mainClass=$testClass" "-Dexec.classpathScope=compile" -q
} else {
    Write-Host "Compilation failed!" -ForegroundColor Red
    exit 1
}

if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Green
    Write-Host " All Reconciliation Tests Passed!" -ForegroundColor Green
    Write-Host "========================================" -ForegroundColor Green
} else {
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Red
    Write-Host " Some Tests Failed" -ForegroundColor Red
    Write-Host "========================================" -ForegroundColor Red
    exit 1
}

