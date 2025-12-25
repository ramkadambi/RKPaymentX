# Wells Payment Engine - Java Architecture

## Overview

This document proposes a scalable Java + Kafka + MongoDB + Redis architecture to support **400 TPS (Transactions Per Second)** across FED, CHIPS, and SWIFT payment networks.

## Performance Requirements

- **Throughput**: 400 TPS (24,000 TPM, 1.44M TPD)
- **Latency**: < 2 seconds end-to-end (p95)
- **Availability**: 99.9% uptime
- **Idempotency**: 100% guarantee (no duplicate postings)
- **Data Consistency**: Strong consistency for financial transactions

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                         Ingress Layer                           │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐        │
│  │ SWIFT IN │  │  FED IN  │  │ CHIPS IN │  │  IBT IN  │        │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘        │
│       │             │             │             │               │
│       └─────────────┴─────────────┴─────────────┘               │
│                          │                                       │
│                          ▼                                       │
│              ┌───────────────────────┐                          │
│              │  Canonical PaymentEvent │                          │
│              │    (Kafka Topic)        │                          │
│              └───────────┬───────────┘                          │
└──────────────────────────┼──────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Kafka Event Streaming                        │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  Topic: payments.orchestrator.in (Partitioned by E2E ID)  │  │
│  │  Partitions: 20 (for 400 TPS with 20 TPS per partition)  │  │
│  │  Replication: 3 (for high availability)                   │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Orchestrator Service                        │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  PaymentOrchestrator (State Machine)                      │  │
│  │  - Routes PaymentEvent through satellites sequentially     │  │
│  │  - Tracks state per end_to_end_id                         │  │
│  │  - Publishes to satellite input topics                    │  │
│  │  - Consumes ServiceResults from satellites               │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                            │
        ┌───────────────────┼───────────────────┐
        │                   │                     │
        ▼                   ▼                     ▼
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│   Satellite  │  │   Satellite  │  │   Satellite  │
│   Services   │  │   Services   │  │   Services   │
│  (Parallel)  │  │  (Parallel)  │  │  (Parallel)  │
└──────────────┘  └──────────────┘  └──────────────┘
        │                   │                     │
        └───────────────────┼───────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Satellite Services                           │
│                                                                 │
│  ┌──────────────────┐  ┌──────────────────┐                  │
│  │ Account          │  │ Routing          │                  │
│  │ Validation       │  │ Validation       │                  │
│  │ (Consumer Group) │  │ (Consumer Group) │                  │
│  └──────────────────┘  └──────────────────┘                  │
│                                                                 │
│  ┌──────────────────┐  ┌──────────────────┐                  │
│  │ Sanctions        │  │ Balance          │                  │
│  │ Check            │  │ Check            │                  │
│  │ (Consumer Group) │  │ (Consumer Group) │                  │
│  └──────────────────┘  └──────────────────┘                  │
│                                                                 │
│  ┌──────────────────┐                                          │
│  │ Payment          │                                          │
│  │ Posting          │                                          │
│  │ (Consumer Group) │                                          │
│  └──────────────────┘                                          │
└─────────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Data Layer                                   │
│                                                                 │
│  ┌──────────────────┐  ┌──────────────────┐                  │
│  │   MongoDB         │  │   Redis          │                  │
│  │   - Transactions  │  │   - Idempotency  │                  │
│  │   - Account Data  │  │   - Cache        │                  │
│  │   - Audit Logs    │  │   - Rate Limits  │                  │
│  └──────────────────┘  └──────────────────┘                  │
└─────────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Egress Layer                                  │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐        │
│  │ SWIFT OUT│  │  FED OUT │  │CHIPS OUT │  │IBT SETTLE│        │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘        │
└─────────────────────────────────────────────────────────────────┘
```

## Technology Stack

### Core Technologies
- **Java**: 17+ (LTS)
- **Kafka**: 3.5+ (Event streaming)
- **MongoDB**: 7.0+ (Document database)
- **Redis**: 7.0+ (Cache and idempotency)

### Frameworks & Libraries
- **Spring Boot**: 3.1+ (Application framework)
- **Spring Kafka**: (Kafka integration)
- **Spring Data MongoDB**: (MongoDB integration)
- **Lettuce**: (Redis client)
- **Jackson**: (JSON serialization)
- **Lombok**: (Code generation)
- **Micrometer**: (Metrics)

## Kafka Architecture

### Topic Design

#### 1. Ingress Topic
```
Topic: payments.orchestrator.in
Partitions: 20 (20 TPS per partition = 400 TPS total)
Replication Factor: 3
Retention: 7 days
Key: end_to_end_id (for partitioning)
```

#### 2. Satellite Input Topics
```
payments.step.account_validation
payments.step.routing_validation
payments.step.sanctions_check
payments.step.balance_check
payments.step.payment_posting

Partitions: 20 each
Replication Factor: 3
Key: end_to_end_id
```

#### 3. Service Result Topics
```
service.results.account_validation
service.results.routing_validation
service.results.sanctions_check
service.results.balance_check
service.results.payment_posting

Partitions: 20 each
Replication Factor: 3
Key: end_to_end_id
```

#### 4. Error Topics
```
service.errors.account_validation
service.errors.routing_validation
service.errors.sanctions_check
service.errors.balance_check
service.errors.payment_posting

Partitions: 20 each
Replication Factor: 3
Key: end_to_end_id
```

#### 5. Final Status Topic
```
payments.final.status
Partitions: 20
Replication Factor: 3
Key: end_to_end_id
```

### Partitioning Strategy

**Key**: `end_to_end_id` (hash-based partitioning)

**Benefits**:
- Ensures same payment always goes to same partition
- Maintains ordering per payment
- Enables parallel processing across partitions
- Scales horizontally by adding partitions

**Partition Count Calculation**:
- Target: 400 TPS
- Per partition: 20 TPS (safe throughput)
- Partitions needed: 400 / 20 = 20 partitions
- Buffer: 25 partitions (for peak loads)

### Consumer Groups

Each satellite service uses a **dedicated consumer group**:

```
account-validation-group
routing-validation-group
sanctions-check-group
balance-check-group
payment-posting-group
```

**Scaling**:
- Each consumer group can have multiple instances
- Kafka automatically distributes partitions across instances
- Example: 5 instances of `balance-check-group` = 4 partitions per instance (20 partitions / 5 instances)

## MongoDB Architecture

### Collections

#### 1. Transactions Collection
```json
{
  "_id": ObjectId("..."),
  "end_to_end_id": "E2E-001",  // Unique index
  "transaction_id": "TXN-001",
  "msg_id": "MSG-001",
  "amount": 5000.00,
  "currency": "USD",
  "debit_entry": { ... },
  "credit_entry": { ... },
  "routing_network": "FED",
  "status": "POSTED",
  "posted_timestamp": ISODate("..."),
  "created_timestamp": ISODate("...")
}
```

**Indexes**:
- `end_to_end_id` (unique)
- `transaction_id`
- `posted_timestamp` (for queries)
- `routing_network` (for analytics)

#### 2. Account Balances Collection
```json
{
  "_id": ObjectId("..."),
  "account_id": "WF-VOSTRO-SBI-USD-001",
  "account_type": "VOSTRO",
  "currency": "USD",
  "current_balance": 1000000.00,
  "opening_balance": 1000000.00,
  "last_updated": ISODate("...")
}
```

**Indexes**:
- `account_id` (unique)
- `account_type`

#### 3. Audit Log Collection
```json
{
  "_id": ObjectId("..."),
  "end_to_end_id": "E2E-001",
  "service_name": "balance_check",
  "status": "PASS",
  "timestamp": ISODate("..."),
  "details": { ... }
}
```

**Indexes**:
- `end_to_end_id`
- `timestamp`
- `service_name`

### Connection Pooling

```java
// MongoDB connection pool settings
mongodb.uri=mongodb://cluster1,cluster2,cluster3/payments?replicaSet=rs0
mongodb.connectionsPerHost=50
mongodb.minConnectionsPerHost=10
mongodb.maxWaitTime=5000
mongodb.connectTimeout=10000
mongodb.socketTimeout=30000
```

### Sharding Strategy (Future)

For horizontal scaling beyond single cluster:
- **Shard Key**: `routing_network` (FED, CHIPS, SWIFT, INTERNAL)
- **Shards**: 4 shards (one per network)
- **Benefits**: Isolate network-specific load

## Redis Architecture

### Use Cases

#### 1. Idempotency Check
```
Key: transaction:{end_to_end_id}
Value: "POSTED"
TTL: 24 hours
```

**Implementation**:
```java
// Check if transaction already processed
Boolean exists = redisTemplate.hasKey("transaction:" + endToEndId);
if (exists) {
    return ERROR; // Already processed
}

// After posting, set key
redisTemplate.opsForValue().set(
    "transaction:" + endToEndId,
    "POSTED",
    Duration.ofHours(24)
);
```

#### 2. Account Balance Cache
```
Key: account:balance:{account_id}
Value: {"balance": 1000000.00, "currency": "USD"}
TTL: 5 minutes
```

**Benefits**:
- Reduces MongoDB queries
- Fast balance checks
- Cache invalidation on balance updates

#### 3. BIC/ABA/CHIPS Lookup Cache
```
Key: bic:{bic_code}
Value: {"fed_member": true, "chips_member": false, ...}
TTL: 1 hour
```

#### 4. Rate Limiting
```
Key: rate_limit:{account_id}:{time_window}
Value: count
TTL: time_window
```

### Redis Cluster Configuration

```
Redis Cluster: 6 nodes (3 masters + 3 replicas)
Memory: 16GB per node
Persistence: AOF (Append-Only File)
Replication: Async replication
```

## Service Architecture

### Microservices

#### 1. Ingress Service
- **Instances**: 4 (one per rail: SWIFT, FED, CHIPS, IBT)
- **Scaling**: Horizontal (add instances per rail)
- **Responsibilities**:
  - Parse incoming messages (MT103, pacs.008, etc.)
  - Convert to canonical PaymentEvent
  - Publish to `payments.orchestrator.in`

#### 2. Orchestrator Service
- **Instances**: 3 (for high availability)
- **Scaling**: Vertical (state machine in memory)
- **Responsibilities**:
  - Route PaymentEvent through satellites
  - Track payment state
  - Publish to satellite input topics
  - Consume ServiceResults

#### 3. Satellite Services
Each satellite service:
- **Instances**: 5-10 (depending on load)
- **Scaling**: Horizontal (Kafka consumer groups)
- **Responsibilities**:
  - Process PaymentEvent
  - Enrich PaymentEvent
  - Publish ServiceResult
  - Query MongoDB/Redis as needed

#### 4. Egress Service
- **Instances**: 4 (one per rail)
- **Scaling**: Horizontal
- **Responsibilities**:
  - Consume from `payments.final.status`
  - Generate network-specific messages
  - Send to external networks

## Scaling Strategy

### Horizontal Scaling

#### Kafka Consumers
- Add more consumer instances to consumer groups
- Kafka automatically rebalances partitions
- Example: 5 instances of `balance-check-group` = 4 partitions per instance

#### MongoDB
- Add more replica set members (read scaling)
- Shard collections (write scaling)
- Use read preferences for read-heavy operations

#### Redis
- Add more Redis nodes to cluster
- Redis Cluster automatically shards keys

### Vertical Scaling

#### Application Instances
- Increase JVM heap size (8GB → 16GB)
- Increase CPU cores
- Tune garbage collection

#### MongoDB
- Increase instance size (CPU, RAM, storage)
- Use faster storage (SSD)

## Performance Optimization

### 1. Kafka Optimization
- **Batch Size**: 100-500 messages per batch
- **Linger Time**: 10ms (wait for batching)
- **Compression**: Snappy or LZ4
- **Acks**: 1 (leader acknowledgment)

### 2. MongoDB Optimization
- **Write Concern**: `w:1` (acknowledge write to primary)
- **Read Preference**: `primaryPreferred` (read from primary, fallback to secondary)
- **Connection Pooling**: 50 connections per instance
- **Indexes**: All frequently queried fields indexed

### 3. Redis Optimization
- **Connection Pooling**: 20 connections per instance
- **Pipeline**: Batch multiple commands
- **Lua Scripts**: Atomic operations

### 4. Application Optimization
- **Async Processing**: Use `@Async` for non-blocking operations
- **Connection Pooling**: Reuse connections
- **Caching**: Cache frequently accessed data
- **Batch Processing**: Process multiple messages in batch

## Monitoring & Observability

### Metrics (Micrometer + Prometheus)

#### Application Metrics
- TPS per service
- Latency (p50, p95, p99)
- Error rate
- Kafka consumer lag
- MongoDB query time
- Redis cache hit rate

#### Infrastructure Metrics
- CPU usage
- Memory usage
- Disk I/O
- Network I/O
- Kafka partition lag
- MongoDB replication lag

### Logging (ELK Stack)

- **Structured Logging**: JSON format
- **Log Levels**: INFO, WARN, ERROR
- **Correlation IDs**: `end_to_end_id` in all logs
- **Retention**: 30 days

### Tracing (Jaeger/Zipkin)

- **Distributed Tracing**: Track payment across services
- **Span Tags**: Service name, operation, duration
- **Trace ID**: Correlate logs across services

## Deployment Architecture

### Kubernetes Deployment

```
Namespace: payments-production

Deployments:
- ingress-service: 4 replicas
- orchestrator-service: 3 replicas
- account-validation-service: 5 replicas
- routing-validation-service: 5 replicas
- sanctions-check-service: 5 replicas
- balance-check-service: 10 replicas (high load)
- payment-posting-service: 10 replicas (high load)
- egress-service: 4 replicas

Services:
- ClusterIP services for internal communication
- LoadBalancer for external access (ingress/egress)

ConfigMaps:
- application.yml
- routing_rulesV2.json

Secrets:
- MongoDB connection string
- Redis connection string
- Kafka bootstrap servers
```

### Resource Limits

```yaml
resources:
  requests:
    memory: "2Gi"
    cpu: "1000m"
  limits:
    memory: "4Gi"
    cpu: "2000m"
```

## Disaster Recovery

### Backup Strategy

#### MongoDB
- **Full Backup**: Daily (at 2 AM)
- **Incremental Backup**: Every 6 hours
- **Retention**: 30 days
- **Backup Location**: S3 or Azure Blob

#### Redis
- **RDB Snapshot**: Every 6 hours
- **AOF**: Continuous (append-only file)
- **Replication**: 3 replicas per master

#### Kafka
- **Topic Retention**: 7 days
- **Log Compaction**: For state stores
- **Replication**: 3 replicas per partition

### Failover Strategy

#### MongoDB
- **Replica Set**: Automatic failover (primary → secondary)
- **Failover Time**: < 30 seconds

#### Redis
- **Cluster Mode**: Automatic failover
- **Failover Time**: < 10 seconds

#### Kafka
- **Replication**: 3 replicas per partition
- **Leader Election**: Automatic
- **Failover Time**: < 5 seconds

## Security

### Authentication & Authorization
- **Kafka**: SASL/SCRAM authentication
- **MongoDB**: X.509 certificates or SCRAM-SHA-256
- **Redis**: AUTH password
- **Application**: OAuth2/JWT tokens

### Encryption
- **In Transit**: TLS 1.3 for all connections
- **At Rest**: MongoDB encryption, Redis encryption

### Network Security
- **VPC**: Isolated network
- **Security Groups**: Restrict access
- **Firewall**: Allow only necessary ports

## Cost Estimation (AWS)

### Monthly Costs (400 TPS)

#### Compute (EC2/EKS)
- Application instances: $2,000
- Kafka brokers: $1,500
- MongoDB cluster: $3,000
- Redis cluster: $1,000

#### Storage
- MongoDB: $500
- Kafka: $300
- Redis: $200

#### Network
- Data transfer: $200

**Total**: ~$8,700/month

## Migration Path

### Phase 1: Core Services (Weeks 1-4)
1. Set up Kafka cluster
2. Set up MongoDB cluster
3. Set up Redis cluster
4. Implement canonical model (Java)
5. Implement orchestrator service

### Phase 2: Satellite Services (Weeks 5-8)
1. Implement account validation service
2. Implement routing validation service
3. Implement sanctions check service
4. Implement balance check service
5. Implement payment posting service

### Phase 3: Ingress/Egress (Weeks 9-12)
1. Implement SWIFT ingress/egress
2. Implement FED ingress/egress
3. Implement CHIPS ingress/egress
4. Implement IBT ingress/egress

### Phase 4: Testing & Optimization (Weeks 13-16)
1. Load testing (400 TPS)
2. Performance optimization
3. Monitoring setup
4. Documentation

## Next Steps

1. Review and approve architecture
2. Set up development environment
3. Create Java project structure
4. Implement core services
5. Set up CI/CD pipeline
6. Deploy to staging environment
7. Load testing
8. Production deployment

