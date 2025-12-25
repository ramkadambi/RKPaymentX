package com.wellsfargo.payment.egress;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Payment Egress Application.
 * 
 * Main entry point for all egress services.
 * 
 * This application runs:
 * - SwiftEgressService (pacs.008, pacs.009)
 * - FedEgressService (pacs.009)
 * - ChipsEgressService (pacs.009)
 * - IbtEgressService (internal settlement)
 * 
 * Each service:
 * - Consumes final PaymentEvent from payments.final.status
 * - Reads RoutingContext to determine routing network
 * - Generates outbound messages based on selected network
 * - Publishes to rail-specific egress topics
 */
@SpringBootApplication
public class EgressApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(EgressApplication.class, args);
    }
}

