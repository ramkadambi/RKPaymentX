# End-to-End Payment Processing Test
# Uses pre-built JARs for faster execution
# Starts all services, then validates the flow

Write-Host "========================================" -ForegroundColor Cyan
Write-Host " End-to-End Payment Processing Test" -ForegroundColor Cyan
Write-Host " Using Pre-built JARs" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

$ErrorActionPreference = "Stop"
$rootDir = Split-Path -Parent $PSScriptRoot

# Configuration
$BOOTSTRAP_SERVERS = "localhost:9092"
$KAFKA_CONTAINER_NAME = "kafka"

# Check Kafka is running
Write-Host "Step 0: Checking Kafka..." -ForegroundColor Yellow
$dockerPsOutput = docker ps --filter "name=$KAFKA_CONTAINER_NAME" --format "{{.Names}}" 2>&1
if ($dockerPsOutput -ne $KAFKA_CONTAINER_NAME) {
    Write-Host "  [ERROR] Kafka container not found. Please start Kafka first." -ForegroundColor Red
    exit 1
}
Write-Host "  [OK] Kafka is running" -ForegroundColor Green
Write-Host ""

# Step 1: Start Orchestrator Service
Write-Host "Step 1: Starting Orchestrator Service..." -ForegroundColor Yellow
$orchestratorJob = Start-Job -ScriptBlock {
    Set-Location $using:rootDir
    Set-Location payment-orchestrator
    & mvn exec:java "-Dexec.mainClass=com.wellsfargo.payment.orchestrator.PaymentOrchestratorApplication" "-Dexec.classpathScope=compile" 2>&1
}
Write-Host "  [OK] Orchestrator service started (Job ID: $($orchestratorJob.Id))" -ForegroundColor Green
Start-Sleep -Seconds 3
Write-Host ""

# Step 2: Start Satellite Services
Write-Host "Step 2: Starting Satellite Services..." -ForegroundColor Yellow

# Account Validation
Write-Host "  Starting Account Validation..." -ForegroundColor Cyan
$accountValidationJob = Start-Job -ScriptBlock {
    Set-Location $using:rootDir
    Set-Location payment-satellites
    & mvn exec:java "-Dexec.mainClass=com.wellsfargo.payment.satellites.accountvalidation.AccountValidationApplication" "-Dexec.classpathScope=compile" 2>&1
}
Write-Host "    [OK] Account Validation started (Job ID: $($accountValidationJob.Id))" -ForegroundColor Green

# Routing Validation
Write-Host "  Starting Routing Validation..." -ForegroundColor Cyan
$routingValidationJob = Start-Job -ScriptBlock {
    Set-Location $using:rootDir
    Set-Location payment-satellites
    & mvn exec:java "-Dexec.mainClass=com.wellsfargo.payment.satellites.routingvalidation.RoutingValidationApplication" "-Dexec.classpathScope=compile" 2>&1
}
Write-Host "    [OK] Routing Validation started (Job ID: $($routingValidationJob.Id))" -ForegroundColor Green

# Sanctions Check
Write-Host "  Starting Sanctions Check..." -ForegroundColor Cyan
$sanctionsCheckJob = Start-Job -ScriptBlock {
    Set-Location $using:rootDir
    Set-Location payment-satellites
    & mvn exec:java "-Dexec.mainClass=com.wellsfargo.payment.satellites.sanctionscheck.SanctionsCheckApplication" "-Dexec.classpathScope=compile" 2>&1
}
Write-Host "    [OK] Sanctions Check started (Job ID: $($sanctionsCheckJob.Id))" -ForegroundColor Green

# Balance Check
Write-Host "  Starting Balance Check..." -ForegroundColor Cyan
$balanceCheckJob = Start-Job -ScriptBlock {
    Set-Location $using:rootDir
    Set-Location payment-satellites
    & mvn exec:java "-Dexec.mainClass=com.wellsfargo.payment.satellites.balancecheck.BalanceCheckApplication" "-Dexec.classpathScope=compile" 2>&1
}
Write-Host "    [OK] Balance Check started (Job ID: $($balanceCheckJob.Id))" -ForegroundColor Green

# Payment Posting
Write-Host "  Starting Payment Posting..." -ForegroundColor Cyan
$paymentPostingJob = Start-Job -ScriptBlock {
    Set-Location $using:rootDir
    Set-Location payment-satellites
    & mvn exec:java "-Dexec.mainClass=com.wellsfargo.payment.satellites.paymentposting.PaymentPostingApplication" "-Dexec.classpathScope=compile" 2>&1
}
Write-Host "    [OK] Payment Posting started (Job ID: $($paymentPostingJob.Id))" -ForegroundColor Green

Write-Host ""
Write-Host "  Waiting for services to initialize (5 seconds)..." -ForegroundColor Gray
Start-Sleep -Seconds 5
Write-Host ""

# Step 3: Fire IBT Payment
Write-Host "Step 3: Firing IBT Payment..." -ForegroundColor Yellow
$INPUT_FILE = "pacs008_wells_final.xml"
$inputFilePath = Join-Path $PSScriptRoot $INPUT_FILE

if (-not (Test-Path $inputFilePath)) {
    Write-Host "  [ERROR] Test file not found: $inputFilePath" -ForegroundColor Red
    Stop-Job $orchestratorJob, $accountValidationJob, $routingValidationJob, $sanctionsCheckJob, $balanceCheckJob, $paymentPostingJob
    Remove-Job $orchestratorJob, $accountValidationJob, $routingValidationJob, $sanctionsCheckJob, $balanceCheckJob, $paymentPostingJob
    exit 1
}

Write-Host "  Processing: $INPUT_FILE" -ForegroundColor Cyan

Push-Location "$rootDir\payment-ingress"
try {
    # Use relative path from payment-ingress directory
    $relativePath = "..\test-data\$INPUT_FILE"
    $execArgs = "--input-file=$relativePath"
    
    $result = & mvn exec:java `
        "-Dexec.mainClass=com.wellsfargo.payment.ingress.swift.SwiftIngressService" `
        "-Dexec.args=$execArgs" `
        "-Dexec.classpathScope=compile" `
        "-q" 2>&1
    
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  [OK] IBT payment published to Kafka" -ForegroundColor Green
    } else {
        Write-Host "  [ERROR] Failed to publish payment" -ForegroundColor Red
        Write-Host "  Error: $result" -ForegroundColor Red
        Stop-Job $orchestratorJob, $accountValidationJob, $routingValidationJob, $sanctionsCheckJob, $balanceCheckJob, $paymentPostingJob
        Remove-Job $orchestratorJob, $accountValidationJob, $routingValidationJob, $sanctionsCheckJob, $balanceCheckJob, $paymentPostingJob
        exit 1
    }
} finally {
    Pop-Location
}

Write-Host ""

# Step 4: Monitor Message Flow
Write-Host "Step 4: Monitoring Message Flow..." -ForegroundColor Yellow
Write-Host ""

function Get-KafkaTopicMessageCount {
    param ([string]$Topic)
    $result = docker exec $KAFKA_CONTAINER_NAME /usr/bin/kafka-run-class kafka.tools.GetOffsetShell `
        --broker-list localhost:9092 --topic $Topic --time -1 2>&1 | 
        Select-String -Pattern "\d+:\d+" | 
        ForEach-Object { ($_ -split ":")[2] } | 
        Measure-Object -Sum
    if ($result.Sum) { return $result.Sum } else { return 0 }
}

$topicsToCheck = @(
    "payments.orchestrator.in",
    "payments.step.account_validation",
    "service.results.account_validation",
    "payments.step.routing_validation",
    "service.results.routing_validation",
    "payments.step.sanctions_check",
    "service.results.sanctions_check",
    "payments.step.balance_check",
    "service.results.balance_check",
    "payments.step.payment_posting",
    "service.results.payment_posting",
    "payments.final.status"
)

$stages = @(
    @{Topic="payments.orchestrator.in"; Name="1. Ingress -> Orchestrator"},
    @{Topic="payments.step.account_validation"; Name="2. Orchestrator -> Account Validation"},
    @{Topic="service.results.account_validation"; Name="3. Account Validation Result"},
    @{Topic="payments.step.routing_validation"; Name="4. -> Routing Validation"},
    @{Topic="service.results.routing_validation"; Name="5. Routing Validation Result"},
    @{Topic="payments.step.sanctions_check"; Name="6. -> Sanctions Check"},
    @{Topic="service.results.sanctions_check"; Name="7. Sanctions Check Result"},
    @{Topic="payments.step.balance_check"; Name="8. -> Balance Check"},
    @{Topic="service.results.balance_check"; Name="9. Balance Check Result"},
    @{Topic="payments.step.payment_posting"; Name="10. -> Payment Posting"},
    @{Topic="service.results.payment_posting"; Name="11. Payment Posting Result"},
    @{Topic="payments.final.status"; Name="12. Final Status"}
)

# Get initial counts
$initialCounts = @{}
foreach ($topic in $topicsToCheck) {
    $initialCounts[$topic] = Get-KafkaTopicMessageCount $topic
}

# Monitor for up to 30 seconds
$maxWait = 30
$checkInterval = 2
$elapsed = 0
$allComplete = $false

while ($elapsed -lt $maxWait -and -not $allComplete) {
    Start-Sleep -Seconds $checkInterval
    $elapsed += $checkInterval
    
    $completed = 0
    foreach ($stage in $stages) {
        $currentCount = Get-KafkaTopicMessageCount $stage.Topic
        $initialCount = $initialCounts[$stage.Topic]
        if (($currentCount - $initialCount) -gt 0) {
            $completed++
        }
    }
    
    if ($completed -eq $stages.Count) {
        $allComplete = $true
    }
    
    Write-Host "  Progress: $completed / $($stages.Count) stages completed (${elapsed}s)" -ForegroundColor Gray
}

Write-Host ""

# Step 5: Final Status Check
Write-Host "Step 5: Final Status Check..." -ForegroundColor Yellow
Write-Host ""

$stageResults = @()
foreach ($stage in $stages) {
    $currentCount = Get-KafkaTopicMessageCount $stage.Topic
    $initialCount = $initialCounts[$stage.Topic]
    $newMessages = $currentCount - $initialCount
    
    $status = if ($newMessages -gt 0) { "[OK]" } else { "[PENDING]" }
    $color = if ($newMessages -gt 0) { "Green" } else { "Yellow" }
    
    Write-Host "$status $($stage.Name)" -ForegroundColor $color
    Write-Host "     Topic: $($stage.Topic)" -ForegroundColor Gray
    Write-Host "     Messages: $currentCount (was $initialCount, +$newMessages)" -ForegroundColor Gray
    Write-Host ""
    
    $stageResults += @{
        Stage = $stage.Name
        Topic = $stage.Topic
        Status = if ($newMessages -gt 0) { "COMPLETE" } else { "PENDING" }
        Messages = $newMessages
    }
}

# Summary
Write-Host "========================================" -ForegroundColor Cyan
Write-Host " Test Summary" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

$completed = ($stageResults | Where-Object { $_.Status -eq "COMPLETE" }).Count
$total = $stageResults.Count

Write-Host "Stages Completed: $completed / $total" -ForegroundColor $(if ($completed -eq $total) { "Green" } else { "Yellow" })
Write-Host ""

foreach ($result in $stageResults) {
    $icon = if ($result.Status -eq "COMPLETE") { "[OK]" } else { "[--]" }
    $color = if ($result.Status -eq "COMPLETE") { "Green" } else { "Yellow" }
    Write-Host "$icon $($result.Stage) - $($result.Status)" -ForegroundColor $color
}

Write-Host ""

if ($completed -eq $total) {
    Write-Host "[SUCCESS] All stages completed successfully!" -ForegroundColor Green
} else {
    Write-Host "[INCOMPLETE] Some stages are pending." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Services are still running in background jobs." -ForegroundColor Cyan
Write-Host "To stop services, run:" -ForegroundColor Cyan
Write-Host "  Stop-Job `$orchestratorJob, `$accountValidationJob, `$routingValidationJob, `$sanctionsCheckJob, `$balanceCheckJob, `$paymentPostingJob" -ForegroundColor Gray
Write-Host "  Remove-Job `$orchestratorJob, `$accountValidationJob, `$routingValidationJob, `$sanctionsCheckJob, `$balanceCheckJob, `$paymentPostingJob" -ForegroundColor Gray
Write-Host ""

