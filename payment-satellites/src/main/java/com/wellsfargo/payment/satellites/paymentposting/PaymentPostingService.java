package com.wellsfargo.payment.satellites.paymentposting;

import com.wellsfargo.payment.canonical.*;
import com.wellsfargo.payment.canonical.enums.RoutingNetwork;
import com.wellsfargo.payment.canonical.enums.ServiceResultStatus;
import com.wellsfargo.payment.satellites.balancecheck.SettlementAccountLookupService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Payment Posting Service for payment processing.
 * 
 * This service:
 * 1. Consumes PaymentEvent from payments.step.payment_posting
 * 2. Enforces idempotency using end_to_end_id (Redis check)
 * 3. Creates explicit debit/credit entries
 * 4. Persists transaction to MongoDB
 * 5. Publishes ServiceResult to service.results.payment_posting
 * 6. Publishes errors to service.errors.payment_posting
 * 
 * Constraints:
 * - Exactly-once semantics per end_to_end_id
 * - Final financial posting
 * - Enforces idempotency
 * - Writes transaction record
 */
@Service
public class PaymentPostingService {
    
    private static final Logger log = LoggerFactory.getLogger(PaymentPostingService.class);
    
    // Topic names
    private static final String TOPIC_INPUT = "payments.step.payment_posting";
    private static final String TOPIC_RESULT = "service.results.payment_posting";
    private static final String TOPIC_ERROR = "service.errors.payment_posting";
    
    private static final String SERVICE_NAME = "payment_posting";
    private static final String WELLS_BIC = "WFBIUS6S";
    private static final BigDecimal AMOUNT_THRESHOLD = new BigDecimal("25000");
    
    @Value("${kafka.bootstrap.servers:localhost:9092}")
    private String bootstrapServers;
    
    @Value("${kafka.consumer.group-id:payment-posting-group}")
    private String groupId;
    
    @Value("${kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;
    
    @Autowired
    private IdempotencyService idempotencyService;
    
    @Autowired
    private TransactionPersistenceService transactionPersistenceService;
    
    @Autowired
    private SettlementAccountLookupService settlementAccountLookupService;
    
    // Kafka consumers and producers
    private KafkaConsumer<String, PaymentEvent> consumer;
    private KafkaProducer<String, ServiceResult> resultProducer;
    
    // Thread management
    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    @PostConstruct
    public void init() {
        log.info("Initializing Payment Posting Service");
        
        // Create consumer
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, 
            com.wellsfargo.payment.kafka.PaymentEventDeserializer.class);
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumerProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        
        consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Collections.singletonList(TOPIC_INPUT));
        
        // Create result producer
        Properties resultProducerProps = new Properties();
        resultProducerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        resultProducerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        resultProducerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, 
            com.wellsfargo.payment.kafka.ServiceResultSerializer.class);
        resultProducerProps.put(ProducerConfig.ACKS_CONFIG, "1");
        resultProducerProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        resultProducerProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        resultProducerProps.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        resultProducerProps.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        
        resultProducer = new KafkaProducer<>(resultProducerProps);
        
        // Start processing thread
        running.set(true);
        executorService = Executors.newSingleThreadExecutor();
        executorService.submit(this::processEvents);
        
        log.info("Payment Posting Service initialized and started");
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down Payment Posting Service");
        
        running.set(false);
        
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        if (consumer != null) {
            consumer.close();
        }
        
        if (resultProducer != null) {
            resultProducer.close();
        }
        
        log.info("Payment Posting Service shut down");
    }
    
    /**
     * Process PaymentEvents from Kafka.
     */
    private void processEvents() {
        log.info("Starting payment posting event processing thread");
        
        while (running.get()) {
            try {
                ConsumerRecords<String, PaymentEvent> records = consumer.poll(Duration.ofSeconds(1));
                
                for (ConsumerRecord<String, PaymentEvent> record : records) {
                    try {
                        PaymentEvent event = record.value();
                        if (event != null) {
                            handleEvent(event);
                            consumer.commitSync();
                        }
                    } catch (Exception e) {
                        log.error("Error processing event: {}", record.key(), e);
                        // Continue processing other records
                    }
                }
            } catch (Exception e) {
                log.error("Error in event processing loop", e);
            }
        }
        
        log.info("Payment posting event processing thread stopped");
    }
    
    /**
     * Handle a PaymentEvent: enforce idempotency, create posting entries, and persist.
     */
    private void handleEvent(PaymentEvent event) {
        String endToEndId = event.getEndToEndId();
        String msgId = event.getMsgId();
        
        log.info("Processing payment: E2E={}, Network={}, Amount={} {}", 
            endToEndId,
            event.getRoutingContext() != null && event.getRoutingContext().getSelectedNetwork() != null
                ? event.getRoutingContext().getSelectedNetwork().getValue() : "NONE",
            event.getAmount(), event.getCurrency());
        
        // Step 1: Enforce idempotency - check if transaction already processed
        if (idempotencyService.isTransactionProcessed(endToEndId)) {
            String errorMsg = String.format("Transaction %s already processed (idempotency check failed)", endToEndId);
            log.error(errorMsg);
            
            ServiceResult result = ServiceResult.builder()
                .endToEndId(endToEndId)
                .serviceName(SERVICE_NAME)
                .status(ServiceResultStatus.ERROR)
                .errorMessage(errorMsg)
                .processingTimestamp(Instant.now().toString())
                .build();
            
            publishError(result);
            return;
        }
        
        // Step 2: Create explicit debit/credit entries
        PostingResult postingResult = createPostingEntries(event);
        
        if (postingResult.getStatus() != ServiceResultStatus.PASS) {
            String errorMsg = String.format("Failed to create posting entries: status=%s", 
                postingResult.getStatus().getValue());
            log.error(errorMsg);
            
            ServiceResult result = ServiceResult.builder()
                .endToEndId(endToEndId)
                .serviceName(SERVICE_NAME)
                .status(postingResult.getStatus())
                .errorMessage(errorMsg)
                .processingTimestamp(Instant.now().toString())
                .build();
            
            publishServiceResult(result);
            if (postingResult.getStatus() == ServiceResultStatus.FAIL || 
                postingResult.getStatus() == ServiceResultStatus.ERROR) {
                publishError(result);
            }
            return;
        }
        
        // Step 3: Log posting entries
        if (postingResult.getEntries() != null && postingResult.getEntries().size() >= 2) {
            PostingEntry debitEntry = postingResult.getEntries().get(0);
            PostingEntry creditEntry = postingResult.getEntries().get(1);
            log.info("Posting entries created: Debit: {} ({}), Credit: {} ({})", 
                debitEntry.getSettlementAccount(), debitEntry.getAccountType(),
                creditEntry.getSettlementAccount(), creditEntry.getAccountType());
        }
        
        // Step 4: Prepare MongoDB document structure
        TransactionDocument transactionDoc = prepareMongoDocument(event, postingResult.getEntries());
        
        // Step 5: Persist to MongoDB
        boolean success = transactionPersistenceService.persistTransaction(transactionDoc);
        
        if (!success) {
            String errorMsg = "Failed to persist transaction to MongoDB";
            log.error(errorMsg);
            
            ServiceResult result = ServiceResult.builder()
                .endToEndId(endToEndId)
                .serviceName(SERVICE_NAME)
                .status(ServiceResultStatus.ERROR)
                .errorMessage(errorMsg)
                .processingTimestamp(Instant.now().toString())
                .build();
            
            publishServiceResult(result);
            publishError(result);
            return;
        }
        
        // Step 6: Publish ServiceResult
        ServiceResult result = ServiceResult.builder()
            .endToEndId(endToEndId)
            .serviceName(SERVICE_NAME)
            .status(ServiceResultStatus.PASS)
            .errorMessage(null)
            .processingTimestamp(Instant.now().toString())
            .build();
        
        log.info("Successfully posted transaction: E2E={}", endToEndId);
        publishServiceResult(result);
    }
    
    /**
     * Create explicit debit and credit entries for payment posting.
     */
    private PostingResult createPostingEntries(PaymentEvent event) {
        // Validate required fields
        String debtorBic = event.getDebtorAgent() != null && event.getDebtorAgent().getIdValue() != null
            ? event.getDebtorAgent().getIdValue().trim() : "";
        String creditorBic = event.getCreditorAgent() != null && event.getCreditorAgent().getIdValue() != null
            ? event.getCreditorAgent().getIdValue().trim() : "";
        
        if (debtorBic.isEmpty() || creditorBic.isEmpty()) {
            return PostingResult.builder()
                .status(ServiceResultStatus.ERROR)
                .entries(Collections.emptyList())
                .build();
        }
        
        // Determine settlement accounts
        SettlementAccountInfo settlementInfo = determineSettlementAccounts(event);
        
        if (settlementInfo.getDebitAccount() == null || settlementInfo.getCreditAccount() == null) {
            return PostingResult.builder()
                .status(ServiceResultStatus.ERROR)
                .entries(Collections.emptyList())
                .build();
        }
        
        // Create timestamp
        String timestamp = Instant.now().toString();
        
        // Determine amounts for posting
        // Debit: Always use original amount (InstdAmt) - customer is debited full amount
        // Credit: Use settlement amount (IntrBkSttlmAmt) if available, otherwise original amount
        BigDecimal debitAmount = event.getAmount(); // Original amount (InstdAmt)
        BigDecimal creditAmount = event.getSettlementAmount() != null 
            ? event.getSettlementAmount()  // Settlement amount after fees (IntrBkSttlmAmt)
            : event.getAmount();  // Fallback to original if settlement amount not set
        
        // Create explicit debit entry (always original amount)
        PostingEntry debitEntry = PostingEntry.builder()
            .endToEndId(event.getEndToEndId())
            .side(EntrySide.DEBIT)
            .agentId(debtorBic)
            .amount(debitAmount)
            .currency(event.getCurrency())
            .settlementAccount(settlementInfo.getDebitAccount())
            .accountType(settlementInfo.getDebitAccountType())
            .timestamp(timestamp)
            .build();
        
        // Create explicit credit entry (settlement amount after fees)
        PostingEntry creditEntry = PostingEntry.builder()
            .endToEndId(event.getEndToEndId())
            .side(EntrySide.CREDIT)
            .agentId(creditorBic)
            .amount(creditAmount)
            .currency(event.getCurrency())
            .settlementAccount(settlementInfo.getCreditAccount())
            .accountType(settlementInfo.getCreditAccountType())
            .timestamp(timestamp)
            .build();
        
        List<PostingEntry> entries = Arrays.asList(debitEntry, creditEntry);
        
        // Business rules validation
        // Rule: FAIL if amount > 25000 (simple control threshold)
        if (event.getAmount().compareTo(AMOUNT_THRESHOLD) > 0) {
            return PostingResult.builder()
                .status(ServiceResultStatus.FAIL)
                .entries(entries)
                .build();
        }
        
        // Rule: FAIL if end_to_end_id ends with 'Z' (legacy rule)
        if (event.getEndToEndId() != null && 
            event.getEndToEndId().trim().toUpperCase().endsWith("Z")) {
            return PostingResult.builder()
                .status(ServiceResultStatus.FAIL)
                .entries(entries)
                .build();
        }
        
        return PostingResult.builder()
            .status(ServiceResultStatus.PASS)
            .entries(entries)
            .build();
    }
    
    /**
     * Determine settlement accounts based on payment routing network and direction.
     */
    private SettlementAccountInfo determineSettlementAccounts(PaymentEvent event) {
        RoutingContext routingContext = event.getRoutingContext();
        
        if (routingContext == null || routingContext.getSelectedNetwork() == null) {
            // No routing decision - use default
            String debtorBic = normalizeBic(event.getDebtorAgent() != null ? event.getDebtorAgent().getIdValue() : null);
            String creditorBic = normalizeBic(event.getCreditorAgent() != null ? event.getCreditorAgent().getIdValue() : null);
            return new SettlementAccountInfo(debtorBic, "CUSTOMER", creditorBic, "CUSTOMER");
        }
        
        RoutingNetwork selectedNetwork = routingContext.getSelectedNetwork();
        String debtorBic = normalizeBic(event.getDebtorAgent() != null ? event.getDebtorAgent().getIdValue() : null);
        String creditorBic = normalizeBic(event.getCreditorAgent() != null ? event.getCreditorAgent().getIdValue() : null);
        String paymentCurrency = event.getCurrency() != null ? event.getCurrency() : "USD";
        
        // Determine payment direction
        // Inbound: Foreign bank (debtor) sending to Wells Fargo (creditor)
        boolean isInbound = !WELLS_BIC.equals(normalizeBic(debtorBic)) && WELLS_BIC.equals(normalizeBic(creditorBic));
        // Outbound: Wells Fargo (debtor) sending to foreign bank (creditor)
        boolean isOutbound = WELLS_BIC.equals(normalizeBic(debtorBic)) && !WELLS_BIC.equals(normalizeBic(creditorBic));
        
        // Check if creditor is Wells customer (for inbound payments)
        // If creditor agent BIC is Wells Fargo, the beneficiary is a Wells customer
        boolean creditorIsWellsCustomer = WELLS_BIC.equals(normalizeBic(creditorBic));
        
        String debitAccount = null;
        String debitAccountType = null;
        String creditAccount = null;
        String creditAccountType = null;
        
        // Determine settlement accounts based on routing network
        if (selectedNetwork == RoutingNetwork.INTERNAL) {
            // IBT: Internal Bank Transfer
            if (isInbound) {
                // Debit from foreign bank's vostro account
                Optional<Map<String, Object>> vostro = settlementAccountLookupService.lookupVostroAccount(debtorBic);
                if (vostro.isPresent()) {
                    debitAccount = (String) vostro.get().get("account_number");
                    debitAccountType = "VOSTRO";
                } else {
                    debitAccount = debtorBic;
                    debitAccountType = "CUSTOMER";
                }
                creditAccount = creditorBic;
                creditAccountType = "CUSTOMER";
            } else {
                // Internal transfer
                debitAccount = debtorBic;
                debitAccountType = "INTERNAL";
                creditAccount = creditorBic;
                creditAccountType = "INTERNAL";
            }
        } else if (selectedNetwork == RoutingNetwork.FED) {
            // FED: Federal Reserve Network
            if (isInbound) {
                // Inbound FED payment
                // Debit: Instructing bank's vostro account (always)
                Optional<Map<String, Object>> vostro = settlementAccountLookupService.lookupVostroAccount(debtorBic);
                if (vostro.isPresent()) {
                    debitAccount = (String) vostro.get().get("account_number");
                    debitAccountType = "VOSTRO";
                } else {
                    debitAccount = debtorBic;
                    debitAccountType = "CUSTOMER";
                }
                
                // Credit: Based on beneficiary
                if (creditorIsWellsCustomer) {
                    // Beneficiary is Wells customer - credit to customer account
                    creditAccount = creditorBic;
                    creditAccountType = "CUSTOMER";
                } else {
                    // Beneficiary is at another bank - credit to FED settlement account
                    Map<String, String> fedAccount = settlementAccountLookupService.lookupFedAccount();
                    creditAccount = fedAccount.get("account_number");
                    creditAccountType = "FED";
                }
            } else {
                // Wells-initiated outbound FED payment
                // Debit: Wells customer account
                debitAccount = debtorBic;
                debitAccountType = "CUSTOMER";
                
                // Credit: FED settlement account
                Map<String, String> fedAccount = settlementAccountLookupService.lookupFedAccount();
                creditAccount = fedAccount.get("account_number");
                creditAccountType = "FED";
            }
        } else if (selectedNetwork == RoutingNetwork.CHIPS) {
            // CHIPS: Clearing House Interbank Payments System
            if (isInbound) {
                // Inbound CHIPS payment
                // Debit: Instructing bank's vostro account (always)
                Optional<Map<String, Object>> vostro = settlementAccountLookupService.lookupVostroAccount(debtorBic);
                if (vostro.isPresent()) {
                    debitAccount = (String) vostro.get().get("account_number");
                    debitAccountType = "VOSTRO";
                } else {
                    debitAccount = debtorBic;
                    debitAccountType = "CUSTOMER";
                }
                
                // Credit: Based on beneficiary
                if (creditorIsWellsCustomer) {
                    // Beneficiary is Wells customer - credit to customer account
                    creditAccount = creditorBic;
                    creditAccountType = "CUSTOMER";
                } else {
                    // Beneficiary is at another bank - credit to receiving bank's CHIPS nostro
                    Optional<Map<String, Object>> chipsNostro = 
                        settlementAccountLookupService.lookupChipsNostroAccount(creditorBic);
                    if (chipsNostro.isPresent()) {
                        creditAccount = (String) chipsNostro.get().get("account_number");
                        creditAccountType = "CHIPS_NOSTRO";
                    } else {
                        // No CHIPS nostro - use FED account as fallback
                        Map<String, String> fedAccount = settlementAccountLookupService.lookupFedAccount();
                        creditAccount = fedAccount.get("account_number");
                        creditAccountType = "FED";
                    }
                }
            } else {
                // Wells-initiated outbound CHIPS payment
                // Debit: Wells customer account
                debitAccount = debtorBic;
                debitAccountType = "CUSTOMER";
                
                // Credit: Receiving bank's CHIPS nostro account
                Optional<Map<String, Object>> chipsNostro = 
                    settlementAccountLookupService.lookupChipsNostroAccount(creditorBic);
                if (chipsNostro.isPresent()) {
                    creditAccount = (String) chipsNostro.get().get("account_number");
                    creditAccountType = "CHIPS_NOSTRO";
                } else {
                    // No CHIPS nostro - use FED account as fallback
                    Map<String, String> fedAccount = settlementAccountLookupService.lookupFedAccount();
                    creditAccount = fedAccount.get("account_number");
                    creditAccountType = "FED";
                }
            }
        } else if (selectedNetwork == RoutingNetwork.SWIFT) {
            // SWIFT: International payment network
            if (isOutbound) {
                // Wells-initiated outbound SWIFT payment
                // Debit: Wells customer account
                debitAccount = debtorBic;
                debitAccountType = "CUSTOMER";
                
                // Credit: Receiving bank's SWIFT nostro account (in payment currency)
                Optional<Map<String, Object>> swiftNostro = 
                    settlementAccountLookupService.lookupSwiftNostroAccount(creditorBic, paymentCurrency);
                if (swiftNostro.isPresent()) {
                    creditAccount = (String) swiftNostro.get().get("account_number");
                    creditAccountType = "SWIFT_NOSTRO";
                } else {
                    creditAccount = creditorBic;
                    creditAccountType = "EXTERNAL";
                }
            } else {
                // Inbound SWIFT payment
                // Debit: Instructing bank's vostro account (always)
                Optional<Map<String, Object>> vostro = settlementAccountLookupService.lookupVostroAccount(debtorBic);
                if (vostro.isPresent()) {
                    debitAccount = (String) vostro.get().get("account_number");
                    debitAccountType = "VOSTRO";
                } else {
                    debitAccount = debtorBic;
                    debitAccountType = "CUSTOMER";
                }
                
                // Credit: Based on beneficiary and routing decision
                if (creditorIsWellsCustomer) {
                    // Beneficiary is Wells customer - credit to customer account
                    creditAccount = creditorBic;
                    creditAccountType = "CUSTOMER";
                } else {
                    // Beneficiary is at another bank - use routing decision
                    // For SWIFT inbound, if routing is SWIFT, credit to receiving bank's SWIFT nostro
                    Optional<Map<String, Object>> swiftNostro = 
                        settlementAccountLookupService.lookupSwiftNostroAccount(creditorBic, paymentCurrency);
                    if (swiftNostro.isPresent()) {
                        creditAccount = (String) swiftNostro.get().get("account_number");
                        creditAccountType = "SWIFT_NOSTRO";
                    } else {
                        // Fallback: credit to creditor BIC (external)
                        creditAccount = creditorBic;
                        creditAccountType = "EXTERNAL";
                    }
                }
            }
        } else {
            // Unknown network - use default
            debitAccount = debtorBic;
            debitAccountType = "CUSTOMER";
            creditAccount = creditorBic;
            creditAccountType = "CUSTOMER";
        }
        
        return new SettlementAccountInfo(debitAccount, debitAccountType, creditAccount, creditAccountType);
    }
    
    /**
     * Normalize BIC to BIC8 format.
     */
    private String normalizeBic(String bic) {
        if (bic == null || bic.trim().isEmpty()) {
            return "";
        }
        String normalized = bic.trim().toUpperCase();
        return normalized.length() > 8 ? normalized.substring(0, 8) : normalized;
    }
    
    /**
     * Prepare MongoDB document structure for transaction persistence.
     */
    private TransactionDocument prepareMongoDocument(PaymentEvent event, List<PostingEntry> entries) {
        // Find debit and credit entries
        PostingEntry debitEntry = null;
        PostingEntry creditEntry = null;
        
        for (PostingEntry entry : entries) {
            if (entry.getSide() == EntrySide.DEBIT) {
                debitEntry = entry;
            } else if (entry.getSide() == EntrySide.CREDIT) {
                creditEntry = entry;
            }
        }
        
        // Convert PostingEntry to map for MongoDB
        Map<String, Object> debitMap = null;
        if (debitEntry != null) {
            debitMap = new HashMap<>();
            debitMap.put("end_to_end_id", debitEntry.getEndToEndId());
            debitMap.put("side", debitEntry.getSide().name());
            debitMap.put("agent_id", debitEntry.getAgentId());
            debitMap.put("amount", debitEntry.getAmount());
            debitMap.put("currency", debitEntry.getCurrency());
            debitMap.put("settlement_account", debitEntry.getSettlementAccount());
            debitMap.put("account_type", debitEntry.getAccountType());
            debitMap.put("timestamp", debitEntry.getTimestamp());
        }
        
        Map<String, Object> creditMap = null;
        if (creditEntry != null) {
            creditMap = new HashMap<>();
            creditMap.put("end_to_end_id", creditEntry.getEndToEndId());
            creditMap.put("side", creditEntry.getSide().name());
            creditMap.put("agent_id", creditEntry.getAgentId());
            creditMap.put("amount", creditEntry.getAmount());
            creditMap.put("currency", creditEntry.getCurrency());
            creditMap.put("settlement_account", creditEntry.getSettlementAccount());
            creditMap.put("account_type", creditEntry.getAccountType());
            creditMap.put("timestamp", creditEntry.getTimestamp());
        }
        
        // Get routing network
        String routingNetwork = null;
        if (event.getRoutingContext() != null && event.getRoutingContext().getSelectedNetwork() != null) {
            routingNetwork = event.getRoutingContext().getSelectedNetwork().getValue();
        }
        
        // Create transaction document
        String timestamp = Instant.now().toString();
        
        return TransactionDocument.builder()
            .endToEndId(event.getEndToEndId())
            .transactionId(event.getTransactionId())
            .msgId(event.getMsgId())
            .amount(event.getAmount())
            .currency(event.getCurrency())
            .debitEntry(debitMap)
            .creditEntry(creditMap)
            .routingNetwork(routingNetwork)
            .status("POSTED")
            .postedTimestamp(timestamp)
            .createdTimestamp(event.getCreatedTimestamp() != null ? event.getCreatedTimestamp() : timestamp)
            .build();
    }
    
    /**
     * Publish ServiceResult to Kafka result topic.
     */
    private void publishServiceResult(ServiceResult result) {
        String endToEndId = result.getEndToEndId();
        
        log.debug("Publishing ServiceResult to topic={}, E2E={}", TOPIC_RESULT, endToEndId);
        
        try {
            ProducerRecord<String, ServiceResult> record = new ProducerRecord<>(
                TOPIC_RESULT, endToEndId, result);
            resultProducer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Failed to publish ServiceResult to topic={}, E2E={}", 
                        TOPIC_RESULT, endToEndId, exception);
                } else {
                    log.debug("Published ServiceResult to topic={}, partition={}, offset={}, E2E={}", 
                        metadata.topic(), metadata.partition(), metadata.offset(), endToEndId);
                }
            });
        } catch (Exception e) {
            log.error("Error publishing ServiceResult to topic={}, E2E={}", TOPIC_RESULT, endToEndId, e);
        }
    }
    
    /**
     * Publish error ServiceResult to Kafka error topic.
     */
    private void publishError(ServiceResult result) {
        String endToEndId = result.getEndToEndId();
        
        log.warn("Publishing error to topic={}, E2E={}, status={}", 
            TOPIC_ERROR, endToEndId, result.getStatus());
        
        try {
            ProducerRecord<String, ServiceResult> record = new ProducerRecord<>(
                TOPIC_ERROR, endToEndId, result);
            resultProducer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Failed to publish error to topic={}, E2E={}", TOPIC_ERROR, endToEndId, exception);
                } else {
                    log.debug("Published error to topic={}, partition={}, offset={}, E2E={}", 
                        metadata.topic(), metadata.partition(), metadata.offset(), endToEndId);
                }
            });
        } catch (Exception e) {
            log.error("Error publishing error to topic={}, E2E={}", TOPIC_ERROR, endToEndId, e);
        }
    }
    
    /**
     * Determine settlement accounts (public method for testing).
     */
    public SettlementAccountInfo determineSettlementAccountsPublic(PaymentEvent event) {
        return determineSettlementAccounts(event);
    }
    
    /**
     * Settlement account information.
     */
    public static class SettlementAccountInfo {
        private final String debitAccount;
        private final String debitAccountType;
        private final String creditAccount;
        private final String creditAccountType;
        
        public SettlementAccountInfo(String debitAccount, String debitAccountType, 
                                     String creditAccount, String creditAccountType) {
            this.debitAccount = debitAccount;
            this.debitAccountType = debitAccountType;
            this.creditAccount = creditAccount;
            this.creditAccountType = creditAccountType;
        }
        
        public String getDebitAccount() {
            return debitAccount;
        }
        
        public String getDebitAccountType() {
            return debitAccountType;
        }
        
        public String getCreditAccount() {
            return creditAccount;
        }
        
        public String getCreditAccountType() {
            return creditAccountType;
        }
    }
    
    /**
     * Posting result.
     */
    @lombok.Builder
    @lombok.Data
    private static class PostingResult {
        private ServiceResultStatus status;
        private List<PostingEntry> entries;
    }
}

