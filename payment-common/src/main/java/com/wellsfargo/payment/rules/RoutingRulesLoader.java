package com.wellsfargo.payment.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Loads routing rules from classpath JSON file.
 * 
 * This class is thread-safe and deterministic.
 * Rules are automatically sorted by priority after loading.
 */
public class RoutingRulesLoader {
    
    private static final Logger log = LoggerFactory.getLogger(RoutingRulesLoader.class);
    private static final String DEFAULT_RULES_PATH = "/config/routing_rulesV2.json";
    
    private final ObjectMapper objectMapper;
    
    public RoutingRulesLoader() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }
    
    /**
     * Load routing rules from the default classpath location.
     * 
     * @return RoutingRulesConfig with rules sorted by priority
     * @throws RuntimeException if rules cannot be loaded
     */
    public RoutingRulesConfig loadRules() {
        return loadRules(DEFAULT_RULES_PATH);
    }
    
    /**
     * Load routing rules from a specific classpath location.
     * 
     * @param classpathPath Path to the JSON file in classpath (e.g., "/config/routing_rulesV2.json")
     * @return RoutingRulesConfig with rules sorted by priority
     * @throws RuntimeException if rules cannot be loaded
     */
    public RoutingRulesConfig loadRules(String classpathPath) {
        try {
            InputStream inputStream = getClass().getResourceAsStream(classpathPath);
            if (inputStream == null) {
                throw new RuntimeException("Routing rules file not found in classpath: " + classpathPath);
            }
            
            RoutingRulesConfig config = objectMapper.readValue(inputStream, RoutingRulesConfig.class);
            
            // Sort rules by priority
            if (config.getRules() != null) {
                List<RoutingRule> sortedRules = config.getRules().stream()
                    .sorted(Comparator.comparing(RoutingRule::getPriority))
                    .collect(Collectors.toList());
                config.setRules(sortedRules);
            }
            
            log.info("Loaded {} routing rules from {}", 
                config.getRules() != null ? config.getRules().size() : 0, 
                classpathPath);
            
            return config;
            
        } catch (Exception e) {
            log.error("Failed to load routing rules from: {}", classpathPath, e);
            throw new RuntimeException("Failed to load routing rules", e);
        }
    }
}

