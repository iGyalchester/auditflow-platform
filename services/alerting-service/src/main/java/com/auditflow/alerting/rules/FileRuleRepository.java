package com.auditflow.alerting.rules;

import com.auditflow.alerting.AlertingProperties;
import com.auditflow.common.model.AlertRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Rules from a JSON array on the classpath or filesystem, loaded at
 * startup, grouped by customer, and upserted into the alert_rules table so
 * (a) alert_history's foreign key is satisfied and (b) the rows are there
 * for the gateway's rule API. The file is the seed; the table is the
 * record.
 */
@Component
public class FileRuleRepository implements RuleRepository {

    private static final Logger log = LoggerFactory.getLogger(FileRuleRepository.class);

    static final String UPSERT_SQL = """
            INSERT INTO alert_rules
                (rule_id, customer_id, name, description, event_type, risk_threshold,
                 condition_expression, enabled, notification_channels)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (rule_id) DO UPDATE SET
                customer_id = EXCLUDED.customer_id, name = EXCLUDED.name,
                description = EXCLUDED.description, event_type = EXCLUDED.event_type,
                risk_threshold = EXCLUDED.risk_threshold,
                condition_expression = EXCLUDED.condition_expression,
                enabled = EXCLUDED.enabled, notification_channels = EXCLUDED.notification_channels
            """;

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final String location;
    private volatile Map<String, List<AlertRule>> byCustomer = Map.of();

    public FileRuleRepository(ResourceLoader resourceLoader, ObjectMapper objectMapper,
                              JdbcTemplate jdbcTemplate, AlertingProperties props) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
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
            syncToDatabase(rules);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read rules file " + location, e);
        }
    }

    private void syncToDatabase(List<AlertRule> rules) {
        try {
            for (AlertRule rule : rules) {
                jdbcTemplate.update(UPSERT_SQL,
                        rule.getRuleId(), rule.getCustomerId(), rule.getName(), rule.getDescription(),
                        rule.getEventType() != null ? rule.getEventType().name() : null,
                        rule.getRiskThreshold() != null ? rule.getRiskThreshold().name() : null,
                        rule.getConditionExpression(), rule.isEnabled(),
                        String.join(",", rule.getNotificationChannels()));
            }
            log.info("Synced {} rules into alert_rules", rules.size());
        } catch (Exception e) {
            // Rules still match from memory; only alert_history (FK) suffers until the DB is back.
            log.error("Could not sync rules into alert_rules: {}", e.toString());
        }
    }

    @Override
    public List<AlertRule> rulesFor(String customerId) {
        return byCustomer.getOrDefault(customerId, List.of());
    }
}
