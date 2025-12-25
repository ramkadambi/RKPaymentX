Write-Host "========================================" -ForegroundColor Cyan
Write-Host " Checking IBT Payment Flow - Detailed" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

$END_TO_END_ID = "E2E-IBT-WF-20250115-001234"
$BOOTSTRAP = "localhost:9092"
$KAFKA_CONTAINER = "kafka"

Write-Host "Payment EndToEndId: $END_TO_END_ID" -ForegroundColor Yellow
Write-Host ""

# Function to get topic offset info
function Get-TopicOffset {
    param([string]$TopicName)
    
    $result = docker exec $KAFKA_CONTAINER /usr/bin/kafka-run-class kafka.tools.GetOffsetShell `
        --broker-list $BOOTSTRAP `
        --topic $TopicName `
        2>&1
    
    if ($result -and $result -notmatch "error|ERROR") {
        $offsets = $result | Select-String -Pattern ":\d+:\d+"
        if ($offsets) {
            $total = 0
            $offsets | ForEach-Object {
                $parts = ($_ -split ":")
                if ($parts.Length -eq 3) {
                    $total += [int]$parts[2]
                }
            }
            return $total
        }
    }
    return 0
}

# Function to check consumer group lag
function Get-ConsumerGroupLag {
    param(
        [string]$GroupId,
        [string]$TopicName
    )
    
    $result = docker exec $KAFKA_CONTAINER /usr/bin/kafka-consumer-groups `
        --bootstrap-server $BOOTSTRAP `
        --group $GroupId `
        --describe `
        2>&1
    
    if ($result -and $result -notmatch "error|ERROR|could not be") {
        $topicLines = $result | Select-String -Pattern $TopicName
        if ($topicLines) {
            $line = $topicLines | Select-Object -First 1
            $parts = $line -split '\s+'
            if ($parts.Length -ge 6) {
                return @{
                    CurrentOffset = [int]$parts[3]
                    LogEndOffset = [int]$parts[4]
                    Lag = [int]$parts[5]
                }
            }
        }
    }
    return $null
}

Write-Host "Topic Message Counts:" -ForegroundColor Magenta
Write-Host "================================" -ForegroundColor Magenta

$topics = @(
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

foreach ($topic in $topics) {
    $count = Get-TopicOffset $topic
    Write-Host "$topic : $count messages" -ForegroundColor $(if ($count -gt 0) { "Green" } else { "Gray" })
}

Write-Host ""
Write-Host "Consumer Group Status:" -ForegroundColor Magenta
Write-Host "================================" -ForegroundColor Magenta

$consumerGroups = @(
    @{GroupId="orchestrator-ingress"; Topic="payments.orchestrator.in"},
    @{GroupId="account-validation"; Topic="payments.step.account_validation"},
    @{GroupId="routing-validation"; Topic="payments.step.routing_validation"},
    @{GroupId="sanctions-check"; Topic="payments.step.sanctions_check"},
    @{GroupId="balance-check"; Topic="payments.step.balance_check"},
    @{GroupId="posting-check"; Topic="payments.step.payment_posting"}
)

foreach ($cg in $consumerGroups) {
    $lag = Get-ConsumerGroupLag $cg.GroupId $cg.Topic
    if ($lag) {
        Write-Host "$($cg.GroupId):" -ForegroundColor Cyan
        Write-Host "  Current: $($lag.CurrentOffset), End: $($lag.LogEndOffset), Lag: $($lag.Lag)" -ForegroundColor $(if ($lag.Lag -eq 0) { "Green" } else { "Yellow" })
    } else {
        Write-Host "$($cg.GroupId): Not consuming or not found" -ForegroundColor Gray
    }
}

Write-Host ""
Write-Host "Checking for payment ID in final status topic:" -ForegroundColor Magenta
Write-Host "================================" -ForegroundColor Magenta

# Try to read from final status topic
$finalCheck = docker exec $KAFKA_CONTAINER /usr/bin/kafka-console-consumer `
    --bootstrap-server $BOOTSTRAP `
    --topic payments.final.status `
    --from-beginning `
    --max-messages 20 `
    --timeout-ms 3000 `
    2>&1 | Select-String -Pattern $END_TO_END_ID

if ($finalCheck) {
    Write-Host "[FOUND] Payment found in final status topic!" -ForegroundColor Green
    Write-Host $finalCheck -ForegroundColor Green
} else {
    Write-Host "[NOT FOUND] Payment not in final status topic yet" -ForegroundColor Yellow
    Write-Host "  (This could mean services are still processing, or services are not running)" -ForegroundColor Gray
}

Write-Host ""

