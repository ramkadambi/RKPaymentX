# Java Project Structure

## Maven Multi-Module Project

```
WellsPaymentEngine/
├── pom.xml                          # Parent POM
├── payment-common/                  # Shared code (canonical models, utilities)
│   ├── pom.xml
│   └── src/main/java/com/wellsfargo/payment/
│       ├── canonical/               # Canonical model classes
│       │   ├── PaymentEvent.java
│       │   ├── Agent.java
│       │   ├── Party.java
│       │   ├── EnrichmentContext.java
│       │   ├── RoutingContext.java
│       │   ├── ServiceResult.java
│       │   └── enums/
│       │       ├── RoutingNetwork.java
│       │       ├── PaymentDirection.java
│       │       ├── MessageSource.java
│       │       └── ServiceResultStatus.java
│       ├── kafka/                   # Kafka utilities
│       │   ├── KafkaConfig.java
│       │   ├── PaymentEventSerializer.java
│       │   └── PaymentEventDeserializer.java
│       ├── mongo/                   # MongoDB utilities
│       │   ├── MongoConfig.java
│       │   ├── TransactionRepository.java
│       │   └── TransactionDocument.java
│       └── redis/                   # Redis utilities
│           ├── RedisConfig.java
│           └── IdempotencyService.java
│
├── payment-orchestrator/            # Orchestrator service
│   ├── pom.xml
│   └── src/main/java/com/wellsfargo/payment/orchestrator/
│       ├── PaymentOrchestrator.java
│       ├── PaymentStateMachine.java
│       ├── OrchestratorConfig.java
│       └── OrchestratorApplication.java
│
├── payment-satellites/             # Satellite services
│   ├── pom.xml
│   └── src/main/java/com/wellsfargo/payment/satellites/
│       ├── accountvalidation/       # Account Validation Service
│       │   ├── AccountValidationService.java
│       │   ├── AccountLookupService.java
│       │   └── AccountValidationApplication.java
│       ├── routingvalidation/       # Routing Validation Service
│       │   ├── RoutingValidationService.java
│       │   ├── RoutingRulesEngine.java
│       │   └── RoutingValidationApplication.java
│       ├── sanctionscheck/         # Sanctions Check Service
│       │   ├── SanctionsCheckService.java
│       │   └── SanctionsCheckApplication.java
│       ├── balancecheck/            # Balance Check Service
│       │   ├── BalanceCheckService.java
│       │   ├── SettlementAccountLookup.java
│       │   └── BalanceCheckApplication.java
│       └── paymentposting/          # Payment Posting Service
│           ├── PaymentPostingService.java
│           ├── PostingEntry.java
│           ├── TransactionPersistenceService.java
│           └── PaymentPostingApplication.java
│
├── payment-ingress/                 # Ingress services
│   ├── pom.xml
│   └── src/main/java/com/wellsfargo/payment/ingress/
│       ├── swift/                   # SWIFT Ingress
│       │   ├── SwiftIngressService.java
│       │   ├── Mt103Parser.java
│       │   └── SwiftIngressApplication.java
│       ├── fed/                     # FED Ingress
│       │   ├── FedIngressService.java
│       │   ├── Pacs008Parser.java
│       │   └── FedIngressApplication.java
│       ├── chips/                   # CHIPS Ingress
│       │   ├── ChipsIngressService.java
│       │   └── ChipsIngressApplication.java
│       └── ibt/                     # IBT Ingress
│           ├── IbtIngressService.java
│           └── IbtIngressApplication.java
│
├── payment-egress/                  # Egress services
│   ├── pom.xml
│   └── src/main/java/com/wellsfargo/payment/egress/
│       ├── swift/                   # SWIFT Egress
│       │   ├── SwiftEgressService.java
│       │   ├── Mt103Generator.java
│       │   ├── Mt202Generator.java
│       │   └── SwiftEgressApplication.java
│       ├── fed/                     # FED Egress
│       │   ├── FedEgressService.java
│       │   ├── Pacs008Generator.java
│       │   └── FedEgressApplication.java
│       ├── chips/                   # CHIPS Egress
│       │   ├── ChipsEgressService.java
│       │   ├── Pacs009Generator.java
│       │   └── ChipsEgressApplication.java
│       └── ibt/                     # IBT Settlement
│           ├── IbtSettlementService.java
│           └── IbtSettlementApplication.java
│
└── config/                          # Configuration files
    ├── application.yml              # Spring Boot config
    ├── routing_rulesV2.json         # Routing rules
    └── kafka-topics.json            # Kafka topic definitions
```

## Key Java Classes

### Canonical Model (payment-common)

#### PaymentEvent.java
```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentEvent {
    private String msgId;
    private String endToEndId;
    private String transactionId;
    private BigDecimal amount;
    private String currency;
    private PaymentDirection direction;
    private MessageSource sourceMessageType;
    private String sourceMessageRaw;
    private Agent debtorAgent;
    private Agent creditorAgent;
    private Party debtor;
    private Party creditor;
    private PaymentStatus status;
    private String valueDate;
    private String settlementDate;
    private String remittanceInfo;
    private String purposeCode;
    private String chargeBearer;
    private EnrichmentContext enrichmentContext;
    private RoutingContext routingContext;
    private String createdTimestamp;
    private String lastUpdatedTimestamp;
    private String processingState;
    private Map<String, Object> metadata;
}
```

#### RoutingContext.java
```java
@Data
@Builder
public class RoutingContext {
    private RoutingNetwork selectedNetwork;
    private String routingRuleApplied;
    private Integer routingRulePriority;
    private List<Agent> agentChain;
    private Map<String, Object> routingDecision;
    private Map<String, Object> paymentEcosystem;
    private RoutingNetwork customerPreference;
    private Urgency paymentUrgency;
    private Map<String, Object> nextBank;
    private List<RoutingTrace> routingTrace;
}
```

### Kafka Configuration

#### KafkaConfig.java
```java
@Configuration
@EnableKafka
public class KafkaConfig {
    
    @Value("${kafka.bootstrap.servers}")
    private String bootstrapServers;
    
    @Bean
    public ProducerFactory<String, PaymentEvent> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, PaymentEventSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 100);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        return new DefaultKafkaProducerFactory<>(props);
    }
    
    @Bean
    public KafkaTemplate<String, PaymentEvent> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
    
    @Bean
    public ConsumerFactory<String, PaymentEvent> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, PaymentEventDeserializer.class);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "payment-processing-group");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(props);
    }
}
```

### MongoDB Configuration

#### MongoConfig.java
```java
@Configuration
@EnableMongoRepositories
public class MongoConfig {
    
    @Value("${mongodb.uri}")
    private String mongoUri;
    
    @Bean
    public MongoClient mongoClient() {
        return MongoClients.create(mongoUri);
    }
    
    @Bean
    public MongoTemplate mongoTemplate() {
        return new MongoTemplate(mongoClient(), "payments");
    }
}
```

#### TransactionRepository.java
```java
@Repository
public interface TransactionRepository extends MongoRepository<TransactionDocument, String> {
    Optional<TransactionDocument> findByEndToEndId(String endToEndId);
    List<TransactionDocument> findByRoutingNetwork(String routingNetwork);
}
```

### Redis Configuration

#### RedisConfig.java
```java
@Configuration
@EnableRedisRepositories
public class RedisConfig {
    
    @Value("${redis.host}")
    private String redisHost;
    
    @Value("${redis.port}")
    private int redisPort;
    
    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        return new LettuceConnectionFactory(redisHost, redisPort);
    }
    
    @Bean
    public RedisTemplate<String, String> redisTemplate() {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory());
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        return template;
    }
}
```

#### IdempotencyService.java
```java
@Service
public class IdempotencyService {
    
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    
    public boolean isTransactionProcessed(String endToEndId) {
        return Boolean.TRUE.equals(
            redisTemplate.hasKey("transaction:" + endToEndId)
        );
    }
    
    public void markTransactionProcessed(String endToEndId) {
        redisTemplate.opsForValue().set(
            "transaction:" + endToEndId,
            "POSTED",
            Duration.ofHours(24)
        );
    }
}
```

### Satellite Service Example

#### BalanceCheckService.java
```java
@Service
@KafkaListener(topics = "payments.step.balance_check", groupId = "balance-check-group")
public class BalanceCheckService {
    
    @Autowired
    private KafkaTemplate<String, PaymentEvent> kafkaTemplate;
    
    @Autowired
    private SettlementAccountLookup settlementAccountLookup;
    
    @Autowired
    private MongoTemplate mongoTemplate;
    
    @KafkaListener(topics = "payments.step.balance_check")
    public void handlePaymentEvent(
        @Payload PaymentEvent event,
        @Header(KafkaHeaders.RECEIVED_KEY) String key
    ) {
        // Determine debit account
        String debitAccount = settlementAccountLookup.determineDebitAccount(event);
        
        // Check balance in MongoDB
        AccountBalance balance = mongoTemplate.findById(
            debitAccount,
            AccountBalance.class
        );
        
        // Create ServiceResult
        ServiceResult result = ServiceResult.builder()
            .endToEndId(event.getEndToEndId())
            .serviceName("balance_check")
            .status(balance.getCurrentBalance().compareTo(event.getAmount()) >= 0
                ? ServiceResultStatus.PASS
                : ServiceResultStatus.FAIL)
            .build();
        
        // Publish ServiceResult
        kafkaTemplate.send("service.results.balance_check", key, result);
    }
}
```

## Configuration Files

### application.yml
```yaml
spring:
  application:
    name: wells-payment-engine
  
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: com.wellsfargo.payment.kafka.PaymentEventSerializer
    consumer:
      group-id: ${KAFKA_GROUP_ID:payment-processing-group}
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: com.wellsfargo.payment.kafka.PaymentEventDeserializer
      auto-offset-reset: earliest
      enable-auto-commit: false
  
  data:
    mongodb:
      uri: ${MONGODB_URI:mongodb://localhost:27017/payments}
      auto-index-creation: true
    
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 2000ms

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```

## Build & Run

### Build
```bash
mvn clean install
```

### Run Services
```bash
# Orchestrator
java -jar payment-orchestrator/target/payment-orchestrator-1.0.0-SNAPSHOT.jar

# Balance Check Service
java -jar payment-satellites/target/payment-satellites-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=balance-check

# Payment Posting Service
java -jar payment-satellites/target/payment-satellites-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=payment-posting
```

## Docker Deployment

### Dockerfile
```dockerfile
FROM openjdk:17-jre-slim
COPY target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

### docker-compose.yml
```yaml
version: '3.8'
services:
  zookeeper:
    image: confluentinc/cp-zookeeper:latest
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
  
  kafka:
    image: confluentinc/cp-kafka:latest
    depends_on:
      - zookeeper
    environment:
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
  
  mongodb:
    image: mongo:7.0
    ports:
      - "27017:27017"
  
  redis:
    image: redis:7.0
    ports:
      - "6379:6379"
  
  orchestrator:
    build: ./payment-orchestrator
    depends_on:
      - kafka
      - mongodb
      - redis
    environment:
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      MONGODB_URI: mongodb://mongodb:27017/payments
      REDIS_HOST: redis
```

