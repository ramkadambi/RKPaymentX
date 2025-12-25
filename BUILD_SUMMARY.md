# Build Summary - Pre-built JARs

All JARs have been successfully built and are located in their respective `target` directories.

## JAR Locations

### Library JARs (Dependencies)
- **payment-common**: `payment-common/target/payment-common-1.0.0-SNAPSHOT.jar`
  - Contains canonical models, enums, and shared utilities
  - Used by all other modules

### Service JARs
- **payment-orchestrator**: `payment-orchestrator/target/payment-orchestrator-1.0.0-SNAPSHOT.jar`
  - Spring Boot application
  - Main class: `com.wellsfargo.payment.orchestrator.PaymentOrchestratorApplication`
  
- **payment-satellites**: `payment-satellites/target/payment-satellites-1.0.0-SNAPSHOT.jar`
  - Spring Boot application
  - Contains multiple satellite services (account validation, sanctions check, etc.)

- **payment-ingress**: `payment-ingress/target/payment-ingress-1.0.0-SNAPSHOT.jar`
  - CLI-based service (not Spring Boot)
  - Main class: `com.wellsfargo.payment.ingress.swift.SwiftIngressService`

- **payment-egress**: `payment-egress/target/payment-egress-1.0.0-SNAPSHOT.jar`
  - Egress service

## Running Services

### Using Pre-built JARs (Faster)

#### Ingress Service (CLI)
```powershell
# Using Maven exec (recommended - handles classpath automatically)
cd payment-ingress
mvn exec:java -Dexec.mainClass=com.wellsfargo.payment.ingress.swift.SwiftIngressService -Dexec.args="--input-file=..\test-data\pacs008_wells_final.xml"
```

#### Orchestrator Service
```powershell
# Using Maven exec
cd payment-orchestrator
mvn exec:java -Dexec.mainClass=com.wellsfargo.payment.orchestrator.PaymentOrchestratorApplication
```

#### Satellites Service
```powershell
# Using Maven exec
cd payment-satellites
mvn exec:java -Dexec.mainClass=com.wellsfargo.payment.satellites.SatellitesApplication
```

### Using Direct Java (Requires Classpath Setup)

For direct Java execution, you would need to:
1. Build a classpath including all dependencies
2. Use `java -cp` with the full classpath

This is more complex and Maven exec handles it automatically.

## Build Commands

### Build All JARs
```powershell
mvn clean package -DskipTests
```

### Build Specific Module
```powershell
mvn clean package -DskipTests -pl payment-ingress
```

### Rebuild After Code Changes
```powershell
mvn clean compile
# Or full rebuild
mvn clean package -DskipTests
```

## Notes

- All JARs are built with Java 17
- Dependencies are resolved from Maven Central
- Spring Boot services can be run using `mvn exec:java` which automatically handles the classpath
- The ingress service is a CLI tool, not a Spring Boot app, so it's best run via `mvn exec:java`

