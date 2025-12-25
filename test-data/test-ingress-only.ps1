# Simple test script to test ingress service only
# Tests the SwiftIngressService with pacs008_wells_final.xml

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Testing Ingress Service Only" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

$xmlFile = "pacs008_wells_final.xml"
$xmlPath = Join-Path $PSScriptRoot $xmlFile

if (-not (Test-Path $xmlPath)) {
    Write-Host "ERROR: XML file not found: $xmlPath" -ForegroundColor Red
    exit 1
}

Write-Host "Input file: $xmlPath" -ForegroundColor Yellow
Write-Host ""

# Navigate to payment-ingress directory
Push-Location "..\payment-ingress"
try {
    Write-Host "Running SwiftIngressService..." -ForegroundColor Cyan
    Write-Host ""
    
    $relativePath = "..\test-data\$xmlFile"
    $execArgs = "--input-file=$relativePath"
    
    $startTime = Get-Date
    
    & mvn exec:java `
        "-Dexec.mainClass=com.wellsfargo.payment.ingress.swift.SwiftIngressService" `
        "-Dexec.args=$execArgs" `
        "-Dexec.classpathScope=compile" `
        2>&1 | ForEach-Object {
            if ($_ -match "ERROR|Exception|Failed") {
                Write-Host $_ -ForegroundColor Red
            } elseif ($_ -match "INFO|Successfully|Published") {
                Write-Host $_ -ForegroundColor Green
            } else {
                Write-Host $_
            }
        }
    
    $endTime = Get-Date
    $duration = ($endTime - $startTime).TotalSeconds
    
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "Ingress test completed in $([math]::Round($duration, 2)) seconds" -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
    
} catch {
    Write-Host "ERROR: Failed to run ingress service" -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
} finally {
    Pop-Location
}

