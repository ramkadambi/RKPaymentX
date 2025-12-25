# End-to-End Payment Flow Test - No Kafka
# Runs all services from ingress to egress without Kafka
# Saves input and output messages for each step

Write-Host "========================================" -ForegroundColor Cyan
Write-Host " End-to-End Payment Flow Test" -ForegroundColor Cyan
Write-Host " No Kafka - All Services in Sequence" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

$rootDir = Split-Path -Parent $PSScriptRoot
$xmlFile = Join-Path $PSScriptRoot "pacs008_wells_final.xml"

if (-not (Test-Path $xmlFile)) {
    Write-Host "[ERROR] Test XML file not found: $xmlFile" -ForegroundColor Red
    exit 1
}

Write-Host "Test File: $xmlFile" -ForegroundColor Yellow
Write-Host "Output Directory: test-output\" -ForegroundColor Yellow
Write-Host ""

$startTime = Get-Date

Push-Location "$rootDir\payment-ingress"
try {
    Write-Host "Running end-to-end flow test..." -ForegroundColor Cyan
    Write-Host ""
    
    # Use relative path from payment-ingress directory
    $relativePath = "..\test-data\pacs008_wells_final.xml"
    
    & mvn exec:java "-Dexec.mainClass=com.wellsfargo.payment.ingress.test.EndToEndFlowTest" "-Dexec.args=$relativePath" "-Dexec.classpathScope=compile"
    
    $endTime = Get-Date
    $duration = ($endTime - $startTime).TotalSeconds
    
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "Test completed in $([math]::Round($duration, 2)) seconds" -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
    
    if ($LASTEXITCODE -eq 0) {
        Write-Host ""
        Write-Host "[SUCCESS] End-to-end flow completed!" -ForegroundColor Green
        Write-Host ""
        Write-Host "Output files saved in: test-output\" -ForegroundColor Cyan
        Write-Host "  Each step has its own folder with input and output messages" -ForegroundColor Gray
    } else {
        Write-Host ""
        Write-Host "[ERROR] Test failed" -ForegroundColor Red
        exit 1
    }
    
} finally {
    Pop-Location
}

Write-Host ""

