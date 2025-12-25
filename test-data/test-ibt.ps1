Write-Host "========================================" -ForegroundColor Cyan
Write-Host " Testing IBT Payment Flow" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Get absolute path to test file
$testFile = (Resolve-Path "pacs008_wells_final.xml").Path
Write-Host "Input file: $testFile" -ForegroundColor Yellow
Write-Host ""

# Change to payment-ingress directory
Push-Location "..\payment-ingress"

try {
    # Use Maven to run with all dependencies
    Write-Host "Running SwiftIngressService..." -ForegroundColor Cyan
    Write-Host ""
    
    # Use relative path from payment-ingress directory
    $relativePath = "..\test-data\pacs008_wells_final.xml"
    $execArgs = "--input-file=$relativePath"
    
    # Run Maven exec with proper argument escaping
    & mvn exec:java "-Dexec.mainClass=com.wellsfargo.payment.ingress.swift.SwiftIngressService" "-Dexec.args=$execArgs" "-Dexec.classpathScope=compile" 2>&1 | Write-Host
    
    if ($LASTEXITCODE -eq 0) {
        Write-Host ""
        Write-Host "[SUCCESS] IBT payment processed and published to Kafka" -ForegroundColor Green
        Write-Host ""
        Write-Host "Next steps:" -ForegroundColor Cyan
        Write-Host "  1. Check Kafka topic: payments.orchestrator.in" -ForegroundColor Gray
        Write-Host "  2. Verify orchestrator consumed the message" -ForegroundColor Gray
        Write-Host "  3. Check final status topic: payments.final.status" -ForegroundColor Gray
    } else {
        Write-Host ""
        Write-Host "[FAILED] Error processing IBT payment" -ForegroundColor Red
    }
} finally {
    Pop-Location
}

