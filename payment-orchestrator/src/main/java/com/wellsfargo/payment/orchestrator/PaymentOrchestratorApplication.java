package com.wellsfargo.payment.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Payment Orchestrator Application.
 * 
 * Main entry point for the payment orchestrator service.
 * 
 * This service:
 * - Consumes PaymentEvent from payments.orchestrator.in
 * - Routes PaymentEvent through satellites in sequence
 * - Tracks processing state per end_to_end_id
 * - Publishes final status to payments.final.status
 */
@SpringBootApplication
public class PaymentOrchestratorApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(PaymentOrchestratorApplication.class, args);
    }
}

