package com.auditflow.alerting.rules;

import com.auditflow.alerting.AlertingProperties;
import com.auditflow.common.model.AlertRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Rules from a JSON array on the classpath or filesystem, loaded at
 * startup and grouped by customer. Good enough to run the product with a
 * handful of tenants and to keep rules in version control; the Aurora
 * repository replaces this when customers manage rules through the API.
 */
@Component
public class FileRuleRepository implements RuleRepository {

    private static final Logger log = LoggerFactory.getLogger(FileRuleRepository.class);

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final String location;
    private volatile Map<String, List<AlertRule>> byCustomer = Map.of();

    public FileRuleRepository(ResourceLoader resourceLoader, ObjectMapper objectMapper, AlertingProperties props) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.location = props.rulesFile();
    }

    @PostConstruct
    public void load() {
        if (location == null || location.isBlank()) {
            log.warn("No rules file configured (audit.alerting.rules-file): no alerts will fire");
            byCustomer = Map.of();
            return;
        }
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            log.warn("Rules file {} not found: no alerts will fire", location);
            byCustomer = Map.of();
            return;
        }
        try (InputStream in = resource.getInputStream()) {
            List<AlertRule> rules = objectMapper.readValue(in,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, AlertRule.class));
            byCustomer = rules.stream().collect(Collectors.groupingBy(AlertRule::getCustomerId));
            log.info("Loaded {} alert rules for {} customers from {}", rules.size(), byCustomer.size(), location);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read rules file " + location, e);
        }
    }

    @Override
    public List<AlertRule> rulesFor(String customerId) {
        return byCustomer.getOrDefault(customerId, List.of());
    }
}
