package com.wellsfargo.payment.satellites.paymentposting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Payment Posting Service Application.
 * 
 * Main entry point for the payment posting satellite service.
 * 
 * This service:
 * - Consumes PaymentEvent from payments.step.payment_posting
 * - Enforces idempotency using end_to_end_id (Redis check)
 * - Creates explicit debit/credit entries
 * - Persists transaction to MongoDB
 * - Publishes ServiceResult for orchestrator sequencing
 */
@SpringBootApplication
public class PaymentPostingApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(PaymentPostingApplication.class, args);
    }
}

