package com.wellsfargo.payment.satellites.routingvalidation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Routing Validation Service Application.
 * 
 * Main entry point for the routing validation satellite service.
 * 
 * This service:
 * - Consumes enriched PaymentEvent from payments.step.routing_validation
 * - Uses routing_rulesV2.json via RoutingRulesEngine
 * - Evaluates routing rules in priority order
 * - Determines selected_network and agent insertion/substitution
 * - Populates RoutingContext and RoutingTrace
 * - Publishes routed PaymentEvent to payments.step.sanctions_check
 * - Publishes ServiceResult for orchestrator sequencing
 */
@SpringBootApplication
public class RoutingValidationApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(RoutingValidationApplication.class, args);
    }
}

