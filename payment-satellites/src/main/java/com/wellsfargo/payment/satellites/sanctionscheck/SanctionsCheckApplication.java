package com.wellsfargo.payment.satellites.sanctionscheck;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Sanctions Check Service Application.
 * 
 * Main entry point for the sanctions check satellite service.
 * 
 * This service:
 * - Consumes PaymentEvent from payments.step.sanctions_check
 * - Checks creditor account, party name, and agent BIC against sanctions list
 * - Uses mock sanctions list (in-memory) for testing
 * - Publishes ServiceResult for orchestrator sequencing
 * - Flags specific credit accounts for testing sanctions failures
 */
@SpringBootApplication
public class SanctionsCheckApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(SanctionsCheckApplication.class, args);
    }
}

