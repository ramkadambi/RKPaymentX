Write-Host "========================================" -ForegroundColor Cyan
Write-Host " End-to-End Payment Flow Validation" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Configuration
$BOOTSTRAP_SERVERS = "localhost:9092"
$KAFKA_CONTAINER_NAME = "kafka"
$PAYMENT_ID = "E2E-IBT-WF-20250115-001234"

# Detect Kafka command
$KAFKA_COMMAND = ""
$dockerPsOutput = docker ps --filter "name=$KAFKA_CONTAINER_NAME" --format "{{.Names}}" 2>&1
if ($dockerPsOutput -eq $KAFKA_CONTAINER_NAME) {
    Write-Host "[OK] Kafka container found: $KAFKA_CONTAINER_NAME" -ForegroundColor Green
    $KAFKA_COMMAND = "docker exec $KAFKA_CONTAINER_NAME /usr/bin/kafka"
} else {
    Write-Host "[ERROR] Kafka container not found. Please start Kafka first." -ForegroundColor Red
    exit 1
}

Write-Host ""

# Step 1: Check Kafka is healthy
Write-Host "Step 1: Validating Kafka connectivity..." -ForegroundColor Yellow
$kafkaCheck = docker exec $KAFKA_CONTAINER_NAME /usr/bin/kafka-broker-api-versions --bootstrap-server localhost:9092 2>&1 | Select-String -Pattern "localhost:9092" -Quiet
if ($kafkaCheck) {
    Write-Host "  [OK] Kafka is healthy" -ForegroundColor Green
} else {
    Write-Host "  [ERROR] Kafka is not responding" -ForegroundColor Red
    exit 1
}

Write-Host ""

# Step 2: Clear previous messages from topics (optional - commented out for safety)
# Write-Host "Step 2: Clearing previous messages..." -ForegroundColor Yellow
# Write-Host "  [SKIP] Keeping existing messages for comparison" -ForegroundColor Gray

# Step 3: Get initial message counts
Write-Host "Step 2: Getting initial message counts..." -ForegroundColor Yellow
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

$initialCounts = @{}
foreach ($topic in $topicsToCheck) {
    $count = Get-KafkaTopicMessageCount $topic
    $initialCounts[$topic] = $count
    Write-Host "  $topic : $count messages" -ForegroundColor Gray
}

Write-Host ""

# Step 4: Fire IBT payment
Write-Host "Step 3: Firing IBT payment..." -ForegroundColor Yellow
$INPUT_FILE = ".\pacs008_wells_final.xml"
if (-not (Test-Path $INPUT_FILE)) {
    Write-Host "  [ERROR] Test file not found: $INPUT_FILE" -ForegroundColor Red
    exit 1
}

Write-Host "  Processing: $INPUT_FILE" -ForegroundColor Cyan

# Change to payment-ingress directory for Maven
Push-Location "..\payment-ingress"
$inputFilePath = (Resolve-Path "..\test-data\$INPUT_FILE").Path

# Build the exec.args string with proper escaping
$execArgs = "--input-file=$inputFilePath --kafka-bootstrap-servers=$BOOTSTRAP_SERVERS"

# Run Maven exec:java
$result = & mvn exec:java "-Dexec.mainClass=com.wellsfargo.payment.ingress.swift.SwiftIngressService" "-Dexec.args=$execArgs" "-q" 2>&1
$mvnExitCode = $LASTEXITCODE
Pop-Location

if ($mvnExitCode -eq 0) {
    Write-Host "  [OK] IBT payment published to Kafka" -ForegroundColor Green
} else {
    Write-Host "  [ERROR] Failed to publish payment" -ForegroundColor Red
    Write-Host "  Error: $result" -ForegroundColor Red
    Pop-Location
    exit 1
}

Write-Host ""

# Step 5: Wait for processing
Write-Host "Step 4: Waiting for payment processing (10 seconds)..." -ForegroundColor Yellow
Start-Sleep -Seconds 10

Write-Host ""

# Step 6: Check orchestrator consumer groups
Write-Host "Step 5: Checking orchestrator consumer groups..." -ForegroundColor Yellow

function Get-ConsumerGroupInfo {
    param ([string]$Group)
    $output = docker exec $KAFKA_CONTAINER_NAME /usr/bin/kafka-consumer-groups `
        --bootstrap-server localhost:9092 `
        --group $Group `
        --describe 2>&1 | Out-String
    
    if ($output -match "CURRENT-OFFSET.*LOG-END-OFFSET.*LAG") {
        # Extract LAG values
        $lines = $output -split "`n" | Select-String -Pattern "\d+\s+\d+\s+\d+"
        if ($lines) {
            $parts = ($lines[0].Line -split "\s+") | Where-Object { $_ -ne "" }
            return @{
                Current = $parts[3]
                End = $parts[4]
                Lag = $parts[5]
                Active = $output -notmatch "no active members"
            }
        }
    }
    return @{
        Current = "N/A"
        End = "N/A"
        Lag = "N/A"
        Active = $false
    }
}

$orchestratorIngress = Get-ConsumerGroupInfo "orchestrator-ingress"
$orchestratorResults = Get-ConsumerGroupInfo "orchestrator-results"

if ($orchestratorIngress.Active) {
    Write-Host "  [OK] orchestrator-ingress: Active, Lag=$($orchestratorIngress.Lag)" -ForegroundColor Green
} else {
    Write-Host "  [WARNING] orchestrator-ingress: No active members, Lag=$($orchestratorIngress.Lag)" -ForegroundColor Yellow
}

if ($orchestratorResults.Active) {
    Write-Host "  [OK] orchestrator-results: Active, Lag=$($orchestratorResults.Lag)" -ForegroundColor Green
} else {
    Write-Host "  [WARNING] orchestrator-results: No active members, Lag=$($orchestratorResults.Lag)" -ForegroundColor Yellow
}

Write-Host ""

# Step 7: Check message counts after processing
Write-Host "Step 6: Checking message flow at each stage..." -ForegroundColor Yellow
Write-Host ""

$stages = @(
    @{Topic="payments.orchestrator.in"; Name="1. Ingress -> Orchestrator"; Expected="+1"},
    @{Topic="payments.step.account_validation"; Name="2. Orchestrator -> Account Validation"; Expected="+1"},
    @{Topic="service.results.account_validation"; Name="3. Account Validation Result"; Expected="+1"},
    @{Topic="payments.step.routing_validation"; Name="4. -> Routing Validation"; Expected="+1"},
    @{Topic="service.results.routing_validation"; Name="5. Routing Validation Result"; Expected="+1"},
    @{Topic="payments.step.sanctions_check"; Name="6. -> Sanctions Check"; Expected="+1"},
    @{Topic="service.results.sanctions_check"; Name="7. Sanctions Check Result"; Expected="+1"},
    @{Topic="payments.step.balance_check"; Name="8. -> Balance Check"; Expected="+1"},
    @{Topic="service.results.balance_check"; Name="9. Balance Check Result"; Expected="+1"},
    @{Topic="payments.step.payment_posting"; Name="10. -> Payment Posting"; Expected="+1"},
    @{Topic="service.results.payment_posting"; Name="11. Payment Posting Result"; Expected="+1"},
    @{Topic="payments.final.status"; Name="12. Final Status"; Expected="+1"}
)

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

Write-Host ""

# Step 8: Sample message from final status
Write-Host "Step 7: Checking final status message..." -ForegroundColor Yellow
$finalStatusCount = Get-KafkaTopicMessageCount "payments.final.status"
if ($finalStatusCount -gt 0) {
    Write-Host "  [OK] Final status topic has $finalStatusCount messages" -ForegroundColor Green
    Write-Host "  Attempting to consume latest message..." -ForegroundColor Gray
    
    $finalMessage = docker exec $KAFKA_CONTAINER_NAME /usr/bin/kafka-console-consumer `
        --bootstrap-server localhost:9092 `
        --topic payments.final.status `
        --from-beginning `
        --max-messages 1 `
        --timeout-ms 3000 2>&1 | Select-Object -Last 1
    
    if ($finalMessage) {
        Write-Host "  Latest message preview:" -ForegroundColor Cyan
        $preview = if ($finalMessage.Length -gt 200) { $finalMessage.Substring(0, 200) + "..." } else { $finalMessage }
        Write-Host "  $preview" -ForegroundColor Gray
    }
} else {
    Write-Host "  [PENDING] Final status topic is empty" -ForegroundColor Yellow
}

Write-Host ""

# Step 9: Summary
Write-Host "========================================" -ForegroundColor Cyan
Write-Host " Validation Summary" -ForegroundColor Cyan
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
    Write-Host "[INCOMPLETE] Some stages are pending. Check:" -ForegroundColor Yellow
    Write-Host "  1. Orchestrator service is running" -ForegroundColor Cyan
    Write-Host "  2. All satellite services are running" -ForegroundColor Cyan
    Write-Host "  3. Consumer groups are active" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "  Run: .\restart-orchestrator.ps1" -ForegroundColor Cyan
}

Write-Host ""

