package com.wellsfargo.payment.canonical;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wellsfargo.payment.canonical.enums.MessageSource;
import com.wellsfargo.payment.canonical.enums.PaymentDirection;
import com.wellsfargo.payment.canonical.enums.PaymentStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Canonical, network-agnostic payment event model.
 * 
 * This is the core data structure that flows through the payment processing engine.
 * It supports all input rails (SWIFT IN, FED IN, CHIPS IN, IBT IN) and 
 * all output rails (SWIFT OUT, FED OUT, CHIPS OUT, IBT settlement).
 * 
 * Key Design Principles:
 * 1. Network-agnostic: No rail-specific fields
 * 2. Enrichment-based: All enrichment data in enrichmentContext
 * 3. Routing-based: All routing data in routingContext
 * 4. Extensible: Can be extended without breaking existing code
 * 
 * This class is designed for Kafka serialization using Jackson.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaymentEvent {
    /**
     * Unique message identifier.
     */
    @NotBlank
    private String msgId;

    /**
     * End-to-end payment identifier (preserved across rails).
     */
    @NotBlank
    private String endToEndId;

    /**
     * Internal transaction identifier.
     */
    private String transactionId;

    /**
     * Payment amount.
     */
    @NotNull
    @Positive
    private BigDecimal amount;

    /**
     * ISO 4217 currency code (e.g., USD, EUR).
     */
    @NotBlank
    private String currency;

    /**
     * Payment direction relative to Wells Fargo.
     */
    @NotNull
    private PaymentDirection direction;

    /**
     * Original message format/rail.
     */
    private MessageSource sourceMessageType;

    /**
     * Original raw message (for audit purposes).
     */
    private String sourceMessageRaw;

    /**
     * Sending bank/agent.
     */
    @NotNull
    private Agent debtorAgent;

    /**
     * Receiving bank/agent.
     */
    @NotNull
    private Agent creditorAgent;

    /**
     * Actual debtor (customer).
     */
    private Party debtor;

    /**
     * Actual creditor (beneficiary).
     */
    private Party creditor;

    /**
     * Payment lifecycle status.
     */
    @Builder.Default
    private PaymentStatus status = PaymentStatus.RECEIVED;

    /**
     * Value date (ISO 8601 date format: YYYY-MM-DD).
     */
    private String valueDate;

    /**
     * Settlement date (ISO 8601 date format: YYYY-MM-DD).
     */
    private String settlementDate;

    /**
     * Remittance information.
     */
    private String remittanceInfo;

    /**
     * Payment purpose code.
     */
    private String purposeCode;

    /**
     * Charge bearer code (e.g., "SHAR", "DEBT", "CRED").
     */
    private String chargeBearer;

    /**
     * Enrichment context - added by processing satellites.
     * All enrichment fields are optional and additive.
     */
    private EnrichmentContext enrichmentContext;

    /**
     * Routing context - added by routing validation.
     * All routing decisions are expressed via this context.
     */
    private RoutingContext routingContext;

    /**
     * ISO 8601 timestamp of when the payment event was created.
     */
    private String createdTimestamp;

    /**
     * ISO 8601 timestamp of when the payment event was last updated.
     */
    private String lastUpdatedTimestamp;

    /**
     * Current processing state.
     */
    private String processingState;

    /**
     * Additional metadata for extensibility.
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
}

