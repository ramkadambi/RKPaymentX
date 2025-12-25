package com.wellsfargo.payment.satellites.balancecheck;

import com.wellsfargo.payment.canonical.*;
import com.wellsfargo.payment.canonical.enums.RoutingNetwork;
import com.wellsfargo.payment.canonical.enums.ServiceResultStatus;
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
 * Balance Check Service for payment processing.
 * 
 * This service:
 * 1. Consumes PaymentEvent from payments.step.balance_check
 * 2. Determines debit account based on routing decision
 * 3. Supports FED, CHIPS, SWIFT Nostro, and Vostro accounts
 * 4. Uses test_ledger.json as mock data source (read-only)
 * 5. Checks if debit account has sufficient balance
 * 6. Enriches PaymentEvent with balance check results
 * 7. Publishes ServiceResult to service.results.balance_check
 * 8. Publishes errors to service.errors.balance_check
 * 
 * Constraints:
 * - No posting here
 * - No balance mutation
 * - Read-only balance checking
 */
@Service
public class BalanceCheckService {
    
    private static final Logger log = LoggerFactory.getLogger(BalanceCheckService.class);
    
    // Topic names
    private static final String TOPIC_INPUT = "payments.step.balance_check";
    private static final String TOPIC_RESULT = "service.results.balance_check";
    private static final String TOPIC_ERROR = "service.errors.balance_check";
    
    private static final String SERVICE_NAME = "balance_check";
    private static final String WELLS_BIC = "WFBIUS6S";
    
    @Value("${kafka.bootstrap.servers:localhost:9092}")
    private String bootstrapServers;
    
    @Value("${kafka.consumer.group-id:balance-check-group}")
    private String groupId;
    
    @Value("${kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;
    
    @Autowired
    private SettlementAccountLookupService settlementAccountLookupService;
    
    @Autowired
    private LedgerService ledgerService;
    
    // Kafka consumers and producers
    private KafkaConsumer<String, PaymentEvent> consumer;
    private KafkaProducer<String, ServiceResult> resultProducer;
    
    // Thread management
    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    @PostConstruct
    public void init() {
        log.info("Initializing Balance Check Service");
        
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
        
        log.info("Balance Check Service initialized and started");
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down Balance Check Service");
        
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
        
        log.info("Balance Check Service shut down");
    }
    
    /**
     * Process PaymentEvents from Kafka.
     */
    private void processEvents() {
        log.info("Starting balance check event processing thread");
        
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
        
        log.info("Balance check event processing thread stopped");
    }
    
    /**
     * Handle a PaymentEvent: determine debit account and check balance.
     */
    private void handleEvent(PaymentEvent event) {
        String endToEndId = event.getEndToEndId();
        String msgId = event.getMsgId();
        
        log.info("Received PaymentEvent: E2E={}, MsgId={}, Amount={} {}", 
            endToEndId, msgId, event.getAmount(), event.getCurrency());
        
        // Perform balance check
        BalanceCheckResult checkResult = performBalanceCheck(event);
        
        // Enrich PaymentEvent with balance check results
        PaymentEvent enrichedEvent = enrichPaymentEvent(event, checkResult);
        
        // Create ServiceResult
        ServiceResult result = ServiceResult.builder()
            .endToEndId(endToEndId)
            .serviceName(SERVICE_NAME)
            .status(checkResult.getStatus())
            .errorMessage(checkResult.getErrorMessage())
            .processingTimestamp(Instant.now().toString())
            .build();
        
        // Publish ServiceResult
        publishServiceResult(result);
        
        // Publish error if FAIL/ERROR
        if (checkResult.getStatus() == ServiceResultStatus.FAIL || 
            checkResult.getStatus() == ServiceResultStatus.ERROR) {
            publishError(result);
        }
        
        log.info("Completed processing E2E={}, status={}", endToEndId, checkResult.getStatus());
    }
    
    /**
     * Perform balance check: determine debit account and check balance.
     */
    private BalanceCheckResult performBalanceCheck(PaymentEvent event) {
        // Determine debit account from routing decision
        DebitAccountInfo debitAccount = determineDebitAccount(event);
        
        if (debitAccount.getAccountId() == null) {
            return BalanceCheckResult.builder()
                .status(ServiceResultStatus.ERROR)
                .errorMessage("Failed to determine debit account")
                .build();
        }
        
        String accountId = debitAccount.getAccountId();
        String accountType = debitAccount.getAccountType();
        BigDecimal requiredAmount = event.getAmount();
        
        // Check if account exists in ledger
        if (!ledgerService.accountExists(accountId)) {
            log.warn("Account not found in ledger: account={}, type={}, required={}", 
                accountId, accountType, requiredAmount);
            
            return BalanceCheckResult.builder()
                .status(ServiceResultStatus.FAIL)
                .debitAccountId(accountId)
                .accountType(accountType)
                .errorMessage(String.format("Account not found: %s", accountId))
                .build();
        }
        
        // Get account balance
        Optional<BigDecimal> balanceOpt = ledgerService.getAccountBalance(accountId);
        if (balanceOpt.isEmpty()) {
            return BalanceCheckResult.builder()
                .status(ServiceResultStatus.ERROR)
                .debitAccountId(accountId)
                .accountType(accountType)
                .errorMessage("Failed to retrieve account balance")
                .build();
        }
        
        BigDecimal availableBalance = balanceOpt.get();
        
        // Check if sufficient balance
        if (!ledgerService.hasSufficientBalance(accountId, requiredAmount)) {
            log.warn("Insufficient balance: account={}, type={}, required={}, available={}", 
                accountId, accountType, requiredAmount, availableBalance);
            
            return BalanceCheckResult.builder()
                .status(ServiceResultStatus.FAIL)
                .debitAccountId(accountId)
                .accountType(accountType)
                .availableBalance(availableBalance)
                .errorMessage(String.format("Insufficient balance: required=%s, available=%s", 
                    requiredAmount, availableBalance))
                .build();
        }
        
        log.info("Sufficient balance: account={}, type={}, required={}, available={}", 
            accountId, accountType, requiredAmount, availableBalance);
        
        return BalanceCheckResult.builder()
            .status(ServiceResultStatus.PASS)
            .debitAccountId(accountId)
            .accountType(accountType)
            .availableBalance(availableBalance)
            .build();
    }
    
    /**
     * Determine debit account based on routing decision.
     */
    private DebitAccountInfo determineDebitAccount(PaymentEvent event) {
        RoutingContext routingContext = event.getRoutingContext();
        
        if (routingContext == null || routingContext.getSelectedNetwork() == null) {
            // No routing decision yet - use debtor BIC as fallback
            String debtorBic = event.getDebtorAgent() != null && event.getDebtorAgent().getIdValue() != null
                ? event.getDebtorAgent().getIdValue().trim().toUpperCase()
                : "";
            if (debtorBic.length() > 8) {
                debtorBic = debtorBic.substring(0, 8);
            }
            return new DebitAccountInfo(debtorBic, "CUSTOMER");
        }
        
        RoutingNetwork selectedNetwork = routingContext.getSelectedNetwork();
        String debtorBic = normalizeBic(event.getDebtorAgent() != null ? event.getDebtorAgent().getIdValue() : null);
        String creditorBic = normalizeBic(event.getCreditorAgent() != null ? event.getCreditorAgent().getIdValue() : null);
        String debtorCountry = event.getDebtorAgent() != null ? event.getDebtorAgent().getCountry() : "";
        String creditorCountry = event.getCreditorAgent() != null ? event.getCreditorAgent().getCountry() : "";
        
        boolean isInbound = !"US".equals(debtorCountry) && WELLS_BIC.equals(creditorBic);
        boolean isOutbound = WELLS_BIC.equals(debtorBic) && !"US".equals(creditorCountry);
        
        // Determine debit account based on routing network
        if (selectedNetwork == RoutingNetwork.INTERNAL) {
            // IBT: Internal bank transfer
            if (isInbound) {
                // Debit from foreign bank's vostro account
                Optional<Map<String, Object>> vostro = settlementAccountLookupService.lookupVostroAccount(debtorBic);
                if (vostro.isPresent()) {
                    return new DebitAccountInfo(
                        (String) vostro.get().get("account_number"), "VOSTRO");
                } else {
                    return new DebitAccountInfo(debtorBic, "CUSTOMER");
                }
            } else {
                // Internal transfer - use debtor BIC
                return new DebitAccountInfo(debtorBic, "CUSTOMER");
            }
        } else if (selectedNetwork == RoutingNetwork.FED) {
            // FED: Federal Reserve Wire Network
            if (isInbound) {
                // Debit from foreign bank's vostro account
                Optional<Map<String, Object>> vostro = settlementAccountLookupService.lookupVostroAccount(debtorBic);
                if (vostro.isPresent()) {
                    return new DebitAccountInfo(
                        (String) vostro.get().get("account_number"), "VOSTRO");
                } else {
                    return new DebitAccountInfo(debtorBic, "CUSTOMER");
                }
            } else {
                // Outbound FED - debit from FED settlement account
                Map<String, String> fedAccount = settlementAccountLookupService.lookupFedAccount();
                return new DebitAccountInfo(fedAccount.get("account_number"), "FED");
            }
        } else if (selectedNetwork == RoutingNetwork.CHIPS) {
            // CHIPS: Clearing House Interbank Payments System
            if (isInbound) {
                // Debit from foreign bank's vostro account
                Optional<Map<String, Object>> vostro = settlementAccountLookupService.lookupVostroAccount(debtorBic);
                if (vostro.isPresent()) {
                    return new DebitAccountInfo(
                        (String) vostro.get().get("account_number"), "VOSTRO");
                } else {
                    return new DebitAccountInfo(debtorBic, "CUSTOMER");
                }
            } else {
                // Outbound CHIPS - debit from CHIPS nostro account
                Optional<Map<String, Object>> chipsNostro = 
                    settlementAccountLookupService.lookupChipsNostroAccount(creditorBic);
                if (chipsNostro.isPresent()) {
                    return new DebitAccountInfo(
                        (String) chipsNostro.get().get("account_number"), "CHIPS_NOSTRO");
                } else {
                    // No CHIPS nostro relationship - use FED account
                    Map<String, String> fedAccount = settlementAccountLookupService.lookupFedAccount();
                    return new DebitAccountInfo(fedAccount.get("account_number"), "FED");
                }
            }
        } else if (selectedNetwork == RoutingNetwork.SWIFT) {
            // SWIFT: SWIFT Network
            if (isOutbound) {
                // Outbound SWIFT - debit from SWIFT nostro account or customer account
                Optional<Map<String, Object>> swiftNostro = 
                    settlementAccountLookupService.lookupSwiftOutNostroAccount(creditorBic);
                if (swiftNostro.isPresent()) {
                    return new DebitAccountInfo(
                        (String) swiftNostro.get().get("account_number"), "SWIFT_NOSTRO");
                } else {
                    return new DebitAccountInfo(debtorBic, "CUSTOMER");
                }
            } else {
                // Inbound SWIFT - debit from foreign bank's vostro account
                Optional<Map<String, Object>> vostro = settlementAccountLookupService.lookupVostroAccount(debtorBic);
                if (vostro.isPresent()) {
                    return new DebitAccountInfo(
                        (String) vostro.get().get("account_number"), "VOSTRO");
                } else {
                    return new DebitAccountInfo(debtorBic, "CUSTOMER");
                }
            }
        } else {
            // Unknown network - use debtor BIC as fallback
            return new DebitAccountInfo(debtorBic, "CUSTOMER");
        }
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
     * Enrich PaymentEvent with balance check results.
     */
    private PaymentEvent enrichPaymentEvent(PaymentEvent event, BalanceCheckResult checkResult) {
        // Build balance check data
        Map<String, Object> balanceCheckData = new HashMap<>();
        balanceCheckData.put("status", checkResult.getStatus().getValue());
        if (checkResult.getDebitAccountId() != null) {
            balanceCheckData.put("debit_account_id", checkResult.getDebitAccountId());
        }
        if (checkResult.getAccountType() != null) {
            balanceCheckData.put("account_type", checkResult.getAccountType());
        }
        if (checkResult.getAvailableBalance() != null) {
            balanceCheckData.put("available_balance", checkResult.getAvailableBalance());
        }
        balanceCheckData.put("required_amount", event.getAmount());
        balanceCheckData.put("sufficient_funds", checkResult.getStatus() == ServiceResultStatus.PASS);
        
        // Get existing enrichment context
        EnrichmentContext existingContext = event.getEnrichmentContext();
        
        // Create new enrichment context with balance check data
        EnrichmentContext newContext;
        if (existingContext != null) {
            newContext = EnrichmentContext.builder()
                .accountValidation(existingContext.getAccountValidation())
                .bicLookup(existingContext.getBicLookup())
                .abaLookup(existingContext.getAbaLookup())
                .chipsLookup(existingContext.getChipsLookup())
                .sanctionsCheck(existingContext.getSanctionsCheck())
                .balanceCheck(balanceCheckData)
                .settlementAccounts(existingContext.getSettlementAccounts())
                .build();
        } else {
            newContext = EnrichmentContext.builder()
                .balanceCheck(balanceCheckData)
                .build();
        }
        
        // Create enriched PaymentEvent
        return PaymentEvent.builder()
            .msgId(event.getMsgId())
            .endToEndId(event.getEndToEndId())
            .transactionId(event.getTransactionId())
            .amount(event.getAmount())
            .currency(event.getCurrency())
            .direction(event.getDirection())
            .sourceMessageType(event.getSourceMessageType())
            .sourceMessageRaw(event.getSourceMessageRaw())
            .debtorAgent(event.getDebtorAgent())
            .creditorAgent(event.getCreditorAgent())
            .debtor(event.getDebtor())
            .creditor(event.getCreditor())
            .status(event.getStatus())
            .valueDate(event.getValueDate())
            .settlementDate(event.getSettlementDate())
            .remittanceInfo(event.getRemittanceInfo())
            .purposeCode(event.getPurposeCode())
            .chargeBearer(event.getChargeBearer())
            .enrichmentContext(newContext)
            .routingContext(event.getRoutingContext())
            .createdTimestamp(event.getCreatedTimestamp())
            .lastUpdatedTimestamp(Instant.now().toString())
            .processingState(event.getProcessingState())
            .metadata(event.getMetadata())
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
     * Debit account information.
     */
    private static class DebitAccountInfo {
        private final String accountId;
        private final String accountType;
        
        public DebitAccountInfo(String accountId, String accountType) {
            this.accountId = accountId;
            this.accountType = accountType;
        }
        
        public String getAccountId() {
            return accountId;
        }
        
        public String getAccountType() {
            return accountType;
        }
    }
    
    /**
     * Balance check result.
     */
    @lombok.Builder
    @lombok.Data
    private static class BalanceCheckResult {
        private ServiceResultStatus status;
        private String debitAccountId;
        private String accountType;
        private BigDecimal availableBalance;
        private String errorMessage;
    }
}

