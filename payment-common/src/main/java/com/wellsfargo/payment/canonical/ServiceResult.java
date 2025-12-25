package com.wellsfargo.payment.canonical;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wellsfargo.payment.canonical.enums.ServiceResultStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Canonical result emitted by a satellite/service processing a PaymentEvent.
 * 
 * Used for orchestrator sequencing and error handling.
 * This class is rail-agnostic and does not contain any rail-specific fields.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ServiceResult {
    /**
     * End-to-end payment identifier.
     */
    @NotBlank
    private String endToEndId;

    /**
     * Name of the service that produced this result.
     */
    @NotBlank
    private String serviceName;

    /**
     * Status of the service execution.
     */
    @NotNull
    private ServiceResultStatus status;

    /**
     * Error message if status is FAIL or ERROR.
     */
    private String errorMessage;

    /**
     * ISO 8601 timestamp of when the service processed the payment.
     */
    private String processingTimestamp;
}

