# Fast Ingress Test - No Kafka
# Validates XML parsing and PaymentEvent mapping without any Kafka overhead

Write-Host "========================================" -ForegroundColor Cyan
Write-Host " Fast Ingress Test - No Kafka" -ForegroundColor Cyan
Write-Host " Pure Logic Validation" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

$rootDir = Split-Path -Parent $PSScriptRoot
$xmlFile = Join-Path $PSScriptRoot "pacs008_wells_final.xml"

if (-not (Test-Path $xmlFile)) {
    Write-Host "[ERROR] Test XML file not found: $xmlFile" -ForegroundColor Red
    exit 1
}

Write-Host "Test File: $xmlFile" -ForegroundColor Yellow
Write-Host ""

$startTime = Get-Date

Push-Location "$rootDir\payment-ingress"
try {
    Write-Host "Compiling and running validation test..." -ForegroundColor Cyan
    Write-Host ""
    
    $xmlPath = (Resolve-Path $xmlFile).Path
    
    & mvn exec:java "-Dexec.mainClass=com.wellsfargo.payment.ingress.test.IngressValidationTest" "-Dexec.args=../test-data/pacs008_wells_final.xml" "-Dexec.classpathScope=compile"
    
    $endTime = Get-Date
    $duration = ($endTime - $startTime).TotalSeconds
    
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "Test completed in $([math]::Round($duration, 2)) seconds" -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
    
    if ($LASTEXITCODE -eq 0) {
        Write-Host ""
        Write-Host "[SUCCESS] All validations passed!" -ForegroundColor Green
    } else {
        Write-Host ""
        Write-Host "[ERROR] Test failed" -ForegroundColor Red
        exit 1
    }
    
} finally {
    Pop-Location
}

Write-Host ""
