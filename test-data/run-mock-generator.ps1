# Run Mock Payment Flow Generator
# This script compiles and runs the mock generator without Kafka

Write-Host "Building project..." -ForegroundColor Cyan
cd ..
mvn clean compile -DskipTests -q

if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed!" -ForegroundColor Red
    exit 1
}

Write-Host "Running Mock Payment Flow Generator..." -ForegroundColor Cyan
cd test-data

$classpath = "..\payment-common\target\classes;..\payment-orchestrator\target\classes"
$libs = Get-ChildItem -Path "..\payment-common\target\lib" -Filter "*.jar" -ErrorAction SilentlyContinue
if ($libs) {
    $classpath += ";" + ($libs | ForEach-Object { $_.FullName } | Join-String -Separator ";")
}

# Add Maven dependencies from local repository
$mavenRepo = "$env:USERPROFILE\.m2\repository"
$jacksonLibs = Get-ChildItem -Path "$mavenRepo\com\fasterxml\jackson\core" -Recurse -Filter "*.jar" -ErrorAction SilentlyContinue | Select-Object -First 3
if ($jacksonLibs) {
    $classpath += ";" + ($jacksonLibs | ForEach-Object { $_.FullName } | Join-String -Separator ";")
}

java -cp "$classpath" com.wellsfargo.payment.test.MockPaymentFlowGenerator

if ($LASTEXITCODE -eq 0) {
    Write-Host "`n✓ Mock payment flow generated successfully!" -ForegroundColor Green
    Write-Host "✓ Files saved to: sample/" -ForegroundColor Green
} else {
    Write-Host "`n✗ Generation failed!" -ForegroundColor Red
    exit 1
}

