Write-Host "========================================" -ForegroundColor Cyan
Write-Host " Checking IBT Payment Flow Progress" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

$END_TO_END_ID = "E2E-IBT-WF-20250115-001234"
$BOOTSTRAP = "localhost:9092"
$KAFKA_CONTAINER = "kafka"

Write-Host "Payment EndToEndId: $END_TO_END_ID" -ForegroundColor Yellow
Write-Host ""

# Function to check topic for messages
function Check-Topic {
    param(
        [string]$TopicName,
        [string]$Description,
        [int]$MaxMessages = 10
    )
    
    Write-Host "Checking: $Description" -ForegroundColor Cyan
    Write-Host "  Topic: $TopicName" -ForegroundColor Gray
    
    $result = docker exec $KAFKA_CONTAINER /usr/bin/kafka-console-consumer `
        --bootstrap-server $BOOTSTRAP `
        --topic $TopicName `
        --from-beginning `
        --max-messages $MaxMessages `
        --timeout-ms 2000 `
        2>&1
    
    if ($result -and $result -notmatch "error|ERROR|Exception") {
        $lines = $result | Where-Object { $_ -match $END_TO_END_ID }
        if ($lines) {
            Write-Host "  [FOUND] Payment found in topic" -ForegroundColor Green
            # Show first matching line (truncated)
            $firstMatch = ($lines | Select-Object -First 1)
            if ($firstMatch.Length -gt 100) {
                Write-Host "  Preview: $($firstMatch.Substring(0, 100))..." -ForegroundColor Gray
            } else {
                Write-Host "  Preview: $firstMatch" -ForegroundColor Gray
            }
            return $true
        } else {
            Write-Host "  [NOT FOUND] Payment not found in recent messages" -ForegroundColor Yellow
            return $false
        }
    } else {
        Write-Host "  [ERROR] Could not read topic" -ForegroundColor Red
        if ($result) {
            Write-Host "  Error: $result" -ForegroundColor Red
        }
        return $false
    }
}

# Function to check topic message count
function Get-TopicMessageCount {
    param([string]$TopicName)
    
    $result = docker exec $KAFKA_CONTAINER /usr/bin/kafka-run-class kafka.tools.GetOffsetShell `
        --broker-list $BOOTSTRAP `
        --topic $TopicName `
        2>&1
    
    if ($result -and $result -notmatch "error|ERROR") {
        $offsets = $result | Select-String -Pattern "\d+:\d+"
        if ($offsets) {
            $total = 0
            $offsets | ForEach-Object {
                $parts = $_ -split ":"
                if ($parts.Length -eq 3) {
                    $total += [int]$parts[2]
                }
            }
            return $total
        }
    }
    return 0
}

Write-Host "Stage 1: INGRESS" -ForegroundColor Magenta
Write-Host "================================" -ForegroundColor Magenta
$ingress = Check-Topic "payments.orchestrator.in" "Ingress → Orchestrator" 5
Write-Host ""

Write-Host "Stage 2: ACCOUNT VALIDATION" -ForegroundColor Magenta
Write-Host "================================" -ForegroundColor Magenta
$acctValInput = Check-Topic "payments.step.account_validation" "Orchestrator → Account Validation" 5
$acctValResult = Check-Topic "service.results.account_validation" "Account Validation Result" 5
Write-Host ""

Write-Host "Stage 3: ROUTING VALIDATION" -ForegroundColor Magenta
Write-Host "================================" -ForegroundColor Magenta
$routingValInput = Check-Topic "payments.step.routing_validation" "Account Validation → Routing Validation" 5
$routingValResult = Check-Topic "service.results.routing_validation" "Routing Validation Result" 5
Write-Host ""

Write-Host "Stage 4: SANCTIONS CHECK" -ForegroundColor Magenta
Write-Host "================================" -ForegroundColor Magenta
$sanctionsInput = Check-Topic "payments.step.sanctions_check" "Routing Validation → Sanctions Check" 5
$sanctionsResult = Check-Topic "service.results.sanctions_check" "Sanctions Check Result" 5
Write-Host ""

Write-Host "Stage 5: BALANCE CHECK" -ForegroundColor Magenta
Write-Host "================================" -ForegroundColor Magenta
$balanceInput = Check-Topic "payments.step.balance_check" "Orchestrator → Balance Check" 5
$balanceResult = Check-Topic "service.results.balance_check" "Balance Check Result" 5
Write-Host ""

Write-Host "Stage 6: PAYMENT POSTING" -ForegroundColor Magenta
Write-Host "================================" -ForegroundColor Magenta
$postingInput = Check-Topic "payments.step.payment_posting" "Orchestrator → Payment Posting" 5
$postingResult = Check-Topic "service.results.payment_posting" "Payment Posting Result" 5
Write-Host ""

Write-Host "Stage 7: FINAL STATUS" -ForegroundColor Magenta
Write-Host "================================" -ForegroundColor Magenta
$finalStatus = Check-Topic "payments.final.status" "Orchestrator → Final Status" 5
Write-Host ""

Write-Host "========================================" -ForegroundColor Cyan
Write-Host " Summary" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Ingress:           " -NoNewline
if ($ingress) { Write-Host "[OK] PASSED" -ForegroundColor Green } else { Write-Host "[X] NOT FOUND" -ForegroundColor Red }
Write-Host "Account Validation: " -NoNewline
if ($acctValInput -and $acctValResult) { Write-Host "[OK] PASSED" -ForegroundColor Green } elseif ($acctValInput) { Write-Host "[...] IN PROGRESS" -ForegroundColor Yellow } else { Write-Host "[X] NOT STARTED" -ForegroundColor Red }
Write-Host "Routing Validation: " -NoNewline
if ($routingValInput -and $routingValResult) { Write-Host "[OK] PASSED" -ForegroundColor Green } elseif ($routingValInput) { Write-Host "[...] IN PROGRESS" -ForegroundColor Yellow } else { Write-Host "[X] NOT STARTED" -ForegroundColor Red }
Write-Host "Sanctions Check:    " -NoNewline
if ($sanctionsInput -and $sanctionsResult) { Write-Host "[OK] PASSED" -ForegroundColor Green } elseif ($sanctionsInput) { Write-Host "[...] IN PROGRESS" -ForegroundColor Yellow } else { Write-Host "[X] NOT STARTED" -ForegroundColor Red }
Write-Host "Balance Check:      " -NoNewline
if ($balanceInput -and $balanceResult) { Write-Host "[OK] PASSED" -ForegroundColor Green } elseif ($balanceInput) { Write-Host "[...] IN PROGRESS" -ForegroundColor Yellow } else { Write-Host "[X] NOT STARTED" -ForegroundColor Red }
Write-Host "Payment Posting:    " -NoNewline
if ($postingInput -and $postingResult) { Write-Host "[OK] PASSED" -ForegroundColor Green } elseif ($postingInput) { Write-Host "[...] IN PROGRESS" -ForegroundColor Yellow } else { Write-Host "[X] NOT STARTED" -ForegroundColor Red }
Write-Host "Final Status:       " -NoNewline
if ($finalStatus) { Write-Host "[OK] COMPLETED" -ForegroundColor Green } else { Write-Host "[X] NOT REACHED" -ForegroundColor Red }
Write-Host ""

