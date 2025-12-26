# PowerShell Script to Start Payment Orchestrator for Screenshot Capture
# This script starts the application in mock mode (no Kafka required)

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Payment Orchestrator - Screenshot Mode" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Check if we're in the right directory
if (-not (Test-Path "pom.xml")) {
    Write-Host "Error: pom.xml not found. Please run this script from the payment-orchestrator directory." -ForegroundColor Red
    exit 1
}

# Check if Maven is available
try {
    $mvnVersion = mvn -version 2>&1
    Write-Host "Maven found:" -ForegroundColor Green
    Write-Host ($mvnVersion | Select-Object -First 1) -ForegroundColor Gray
} catch {
    Write-Host "Error: Maven not found. Please install Maven and add it to your PATH." -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "Checking application.properties..." -ForegroundColor Yellow

# Verify mock mode is enabled
$propsFile = "src\main\resources\application.properties"
if (Test-Path $propsFile) {
    $content = Get-Content $propsFile -Raw
    if ($content -match "error\.management\.mock\.mode=true") {
        Write-Host "✓ Mock mode is enabled" -ForegroundColor Green
    } else {
        Write-Host "⚠ Warning: Mock mode may not be enabled. Checking..." -ForegroundColor Yellow
        if ($content -notmatch "error\.management\.mock\.mode") {
            Write-Host "  Adding mock mode configuration..." -ForegroundColor Yellow
            Add-Content $propsFile "`nerror.management.mock.mode=true"
            Write-Host "✓ Mock mode enabled" -ForegroundColor Green
        }
    }
} else {
    Write-Host "⚠ Warning: application.properties not found" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Starting Payment Orchestrator..." -ForegroundColor Yellow
Write-Host "  - Mock Mode: ENABLED (no Kafka required)" -ForegroundColor Gray
Write-Host "  - Port: 8081" -ForegroundColor Gray
Write-Host "  - UI URL: http://localhost:8081/index.html" -ForegroundColor Gray
Write-Host ""
Write-Host "Press Ctrl+C to stop the application" -ForegroundColor Yellow
Write-Host ""

# Start Maven Spring Boot
try {
    mvn spring-boot:run
} catch {
    Write-Host ""
    Write-Host "Error starting application. Check the error messages above." -ForegroundColor Red
    exit 1
}

