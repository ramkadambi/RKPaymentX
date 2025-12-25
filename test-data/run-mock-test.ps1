# Mock Kafka Payment Flow Test
# Fast in-memory test without real Kafka

Write-Host "========================================" -ForegroundColor Cyan
Write-Host " Mock Kafka Payment Flow Test" -ForegroundColor Cyan
Write-Host " Fast In-Memory Test" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

$rootDir = Split-Path -Parent $PSScriptRoot
$testFile = Join-Path $PSScriptRoot "MockPaymentFlowTest.java"
$xmlFile = Join-Path $PSScriptRoot "pacs008_wells_final.xml"

if (-not (Test-Path $xmlFile)) {
    Write-Host "[ERROR] Test XML file not found: $xmlFile" -ForegroundColor Red
    exit 1
}

Write-Host "Step 1: Compiling test class..." -ForegroundColor Yellow

# Create a temp directory for compiled classes
$tempDir = Join-Path $env:TEMP "payment-test-$(Get-Date -Format 'yyyyMMddHHmmss')"
New-Item -ItemType Directory -Path $tempDir -Force | Out-Null

try {
    # Compile the test class
    Push-Location $rootDir
    
    # Build classpath
    $classpath = @(
        "payment-common/target/classes",
        "payment-ingress/target/classes",
        "payment-common/target/payment-common-1.0.0-SNAPSHOT.jar"
    ) | ForEach-Object { Resolve-Path $_ -ErrorAction SilentlyContinue } | Where-Object { $_ } | Join-String -Separator ";"
    
    # Add Maven dependencies to classpath
    $mavenRepo = "$env:USERPROFILE\.m2\repository"
    $deps = @(
        "org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar",
        "org/slf4j/slf4j-simple/1.7.36/slf4j-simple-1.7.36.jar"
    )
    
    foreach ($dep in $deps) {
        $depPath = Join-Path $mavenRepo $dep
        if (Test-Path $depPath) {
            $classpath += ";$depPath"
        }
    }
    
    Write-Host "  Compiling with classpath..." -ForegroundColor Gray
    
    # Copy test file to proper package structure
    $testPackageDir = Join-Path $tempDir "com\wellsfargo\payment\test"
    New-Item -ItemType Directory -Path $testPackageDir -Force | Out-Null
    Copy-Item $testFile (Join-Path $testPackageDir "MockPaymentFlowTest.java")
    
    # Compile
    $javacArgs = @(
        "-d", $tempDir,
        "-cp", $classpath,
        "-sourcepath", $rootDir,
        (Join-Path $testPackageDir "MockPaymentFlowTest.java")
    )
    
    & javac $javacArgs 2>&1 | ForEach-Object {
        if ($_ -match "error") {
            Write-Host $_ -ForegroundColor Red
        } else {
            Write-Host $_ -ForegroundColor Gray
        }
    }
    
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  [ERROR] Compilation failed" -ForegroundColor Red
        exit 1
    }
    
    Write-Host "  [OK] Compilation successful" -ForegroundColor Green
    Write-Host ""
    
    # Run the test
    Write-Host "Step 2: Running mock payment flow test..." -ForegroundColor Yellow
    Write-Host ""
    
    $runClasspath = "$tempDir;$classpath"
    $xmlPath = (Resolve-Path $xmlFile).Path
    
    & java -cp $runClasspath com.wellsfargo.payment.test.MockPaymentFlowTest $xmlPath
    
    if ($LASTEXITCODE -eq 0) {
        Write-Host ""
        Write-Host "[SUCCESS] Mock test completed!" -ForegroundColor Green
    } else {
        Write-Host ""
        Write-Host "[ERROR] Test failed" -ForegroundColor Red
        exit 1
    }
    
} catch {
    Write-Host "[ERROR] $($_.Exception.Message)" -ForegroundColor Red
    exit 1
} finally {
    Pop-Location
    # Cleanup
    if (Test-Path $tempDir) {
        Remove-Item -Path $tempDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

