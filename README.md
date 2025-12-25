# Wells Fargo Multi-Rail Payment Processing Engine

## Project Overview

This is a Java-based commercial payment processing engine for Wells Fargo that supports multiple payment rails:

### Supported Input Rails (Ingress)
- **SWIFT IN**: SWIFT MT messages (MT103, MT202, MT202COV, etc.)
- **FED IN**: Federal Reserve Wire Network messages
- **CHIPS IN**: Clearing House Interbank Payments System messages
- **IBT IN**: Internal Bank Transfer messages

### Supported Output Rails (Egress)
- **SWIFT OUT**: SWIFT MT message generation
- **FED OUT**: FED message generation (pacs.008)
- **CHIPS OUT**: CHIPS message generation (pacs.009)
- **IBT Settlement**: Internal settlement notifications

## Architecture

```
Ingress (Multiple Rails)
  ↓
Canonical PaymentEvent (Kafka Topic)
  ↓
Account Validation (Enrichment)
  ↓
Routing Validation (Rule Engine)
  ↓
Sanctions Check
  ↓
Balance Check (Vostro/Nostro/FED)
  ↓
Posting (Double-Entry Ledger)
  ↓
Egress (FED / CHIPS / SWIFT)
```

## Technology Stack

- **Language**: Java
- **Message Broker**: Apache Kafka
- **Database**: MongoDB
- **Cache**: Redis

## Canonical Model

The canonical `PaymentEvent` model is defined in the Java classes in `payment-common/src/main/java/com/wellsfargo/payment/canonical/`.

### Key Design Principles

1. **Network-Agnostic**: No rail-specific fields in the core model
2. **Enrichment-Based**: All enrichment data stored in `enrichment_context`
3. **Routing-Based**: All routing data stored in `routing_context`
4. **Extensible**: Can be extended without breaking existing code

### Core Components

- **PaymentEvent**: Main canonical event structure
- **Agent**: Network-agnostic financial institution representation
- **Party**: Debtor/creditor party information
- **EnrichmentContext**: All enrichment data from processing satellites
- **RoutingContext**: All routing decision and context data
- **ServiceResult**: Satellite service execution results

## Project Structure

```
WellsPaymentEngine/
├── payment-common/              # Common modules (canonical models, routing rules)
├── payment-ingress/             # Ingress adapters (SWIFT, FED, CHIPS, IBT)
├── payment-egress/              # Egress adapters (SWIFT, FED, CHIPS, IBT)
├── payment-orchestrator/        # Payment orchestration service
├── payment-satellites/          # Processing satellite services
├── test-data/                   # Test XML files and test scripts
├── config/                      # Configuration files (routing rules, etc.)
├── README.md
└── pom.xml
```

## Getting Started

See the individual module README files for detailed setup and run instructions.

### Building the Project

```bash
mvn clean install
```

### Running Services

Each service can be run independently or via Docker/Kubernetes. See `BUILD_SUMMARY.md` for pre-built JAR locations and execution instructions.

