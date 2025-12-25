package com.wellsfargo.payment.ingress;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Payment Ingress Application.
 * 
 * Main entry point for all ingress services.
 * 
 * This application runs:
 * - SwiftIngressService (pacs.008, pacs.009)
 * - FedIngressService (pacs.009)
 * - ChipsIngressService (pacs.009)
 * - IbtIngressService (pacs.008)
 * 
 * Each service:
 * - Parses rail-specific messages
 * - Converts to canonical PaymentEvent
 * - Publishes to payments.orchestrator.in
 */
@SpringBootApplication
public class IngressApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(IngressApplication.class, args);
    }
}

