package com.wellsfargo.payment.satellites.balancecheck;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Balance Check Service Application.
 * 
 * Main entry point for the balance check satellite service.
 * 
 * This service:
 * - Consumes PaymentEvent from payments.step.balance_check
 * - Determines debit account based on routing decision
 * - Supports FED, CHIPS, SWIFT Nostro, and Vostro accounts
 * - Checks balance using mock ledger data (read-only)
 * - Enriches PaymentEvent with balance check results
 * - Publishes ServiceResult for orchestrator sequencing
 */
@SpringBootApplication
public class BalanceCheckApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(BalanceCheckApplication.class, args);
    }
}

