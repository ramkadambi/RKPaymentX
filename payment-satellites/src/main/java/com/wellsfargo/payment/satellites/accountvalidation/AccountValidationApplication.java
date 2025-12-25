package com.wellsfargo.payment.satellites.accountvalidation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Account Validation Service Application.
 * 
 * Main entry point for the account validation satellite service.
 * 
 * This service:
 * - Consumes PaymentEvent from payments.step.account_validation
 * - Performs account & bank enrichment
 * - Publishes enriched PaymentEvent to payments.step.routing_validation
 * - Publishes ServiceResult for orchestrator sequencing
 */
@SpringBootApplication
public class AccountValidationApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(AccountValidationApplication.class, args);
    }
}

