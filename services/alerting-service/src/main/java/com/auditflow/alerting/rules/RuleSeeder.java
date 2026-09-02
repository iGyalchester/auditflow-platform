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

/**
 * Seeds alert_rules from a JSON array on the classpath or filesystem at
 * startup (upsert, so re-deploys refresh the seeded rows without touching
 * rules created through the API). The table is the source of truth -
 * {@link JdbcRuleRepository} reads it - the file is just how a fresh
 * environment gets its first rules.
 */
@Component
public class RuleSeeder {

    private static final Logger log = LoggerFactory.getLogger(RuleSeeder.class);

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
    private volatile List<AlertRule> seeded = List.of();

    public RuleSeeder(ResourceLoader resourceLoader, ObjectMapper objectMapper,
                      JdbcTemplate jdbcTemplate, AlertingProperties props) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.location = props.rulesFile();
    }

    @PostConstruct
    public void load() {
        if (location == null || location.isBlank()) {
            log.info("No rules seed file configured (audit.alerting.rules-file); rules come from alert_rules only");
            return;
        }
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            log.warn("Rules seed file {} not found; rules come from alert_rules only", location);
            return;
        }
        try (InputStream in = resource.getInputStream()) {
            List<AlertRule> rules = objectMapper.readValue(in,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, AlertRule.class));
            seeded = rules;
            log.info("Read {} seed rules from {}", rules.size(), location);
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
            log.info("Seeded {} rules into alert_rules", rules.size());
        } catch (Exception e) {
            log.error("Could not seed rules into alert_rules: {}", e.toString());
        }
    }

    /** What the file contained (for diagnostics/tests); not what alerting matches against. */
    public List<AlertRule> seededRules() {
        return seeded;
    }
}
