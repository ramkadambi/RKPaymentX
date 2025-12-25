Write-Host "==============================="
Write-Host " KAFKA TOPIC EGRESS - STARTED"
Write-Host "==============================="

$KAFKA_CONTAINER = "kafka"
$BOOTSTRAP = "localhost:9092"

$TOPICS = @(
    # Egress topics
    "egress.swift.out",
    "egress.fed.out",
    "egress.chips.out",
    "egress.ibt.settlement"
)

# Try to find kafka-topics command
$KAFKA_TOPICS_CMD = $null

# Try Docker exec first (if Kafka is in a container)
$dockerCheck = docker ps --filter "name=$KAFKA_CONTAINER" --format "{{.Names}}" 2>$null
if ($dockerCheck -eq $KAFKA_CONTAINER) {
    Write-Host "Found Kafka container: $KAFKA_CONTAINER" -ForegroundColor Cyan
    # Try different paths for kafka-topics in container
    $KAFKA_TOPICS_CMD = "docker exec $KAFKA_CONTAINER /usr/bin/kafka-topics"
} else {
    # Try direct command (if in PATH)
    $kafkaCheck = Get-Command kafka-topics.sh -ErrorAction SilentlyContinue
    if ($kafkaCheck) {
        $KAFKA_TOPICS_CMD = "kafka-topics.sh"
    } else {
        # Try Windows batch file
        $kafkaBatchCheck = Get-Command kafka-topics.bat -ErrorAction SilentlyContinue
        if ($kafkaBatchCheck) {
            $KAFKA_TOPICS_CMD = "kafka-topics.bat"
        }
    }
}

if (-not $KAFKA_TOPICS_CMD) {
    Write-Host "ERROR: kafka-topics.sh not found!" -ForegroundColor Red
    Write-Host "Please ensure:" -ForegroundColor Yellow
    Write-Host "  1. Kafka is running (docker or local installation)" -ForegroundColor Yellow
    Write-Host "  2. kafka-topics.sh is in PATH, OR" -ForegroundColor Yellow
    Write-Host "  3. Kafka container is named '$KAFKA_CONTAINER' for Docker exec" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "You can also create topics manually using:" -ForegroundColor Cyan
    foreach ($topic in $TOPICS) {
        Write-Host "  kafka-topics.sh --create --bootstrap-server $BOOTSTRAP --topic $topic --partitions 1 --replication-factor 1" -ForegroundColor Gray
    }
    exit 1
}

Write-Host "Using: $KAFKA_TOPICS_CMD" -ForegroundColor Cyan
Write-Host ""

foreach ($topic in $TOPICS) {
    Write-Host "Creating topic: $topic" -NoNewline
    if ($KAFKA_TOPICS_CMD -like "docker exec *") {
        # Docker exec command
        $result = docker exec $KAFKA_CONTAINER /usr/bin/kafka-topics --create --bootstrap-server $BOOTSTRAP --topic $topic --partitions 1 --replication-factor 1 2>&1
    } else {
        # Local command
        $result = & $KAFKA_TOPICS_CMD --create --bootstrap-server $BOOTSTRAP --topic $topic --partitions 1 --replication-factor 1 2>&1
    }
    
    if ($LASTEXITCODE -eq 0) {
        Write-Host " [OK]" -ForegroundColor Green
    } else {
        if ($result -match "already exists" -or $result -match "TopicExistsException") {
            Write-Host " [EXISTS]" -ForegroundColor Yellow
        } else {
            Write-Host " [FAILED]" -ForegroundColor Red
            Write-Host "  Error: $result" -ForegroundColor Red
        }
    }
}

Write-Host "==============================="
Write-Host " KAFKA TOPIC EGRESS - COMPLETED"
Write-Host "==============================="
