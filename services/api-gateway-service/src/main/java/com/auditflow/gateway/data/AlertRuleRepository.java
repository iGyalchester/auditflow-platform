package com.auditflow.gateway.data;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.model.AlertRule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * A customer's alert rules in alert_rules. Every statement carries the
 * customer id, so a rule id from another tenant is simply "not found".
 */
@Repository
public class AlertRuleRepository {

    static final RowMapper<AlertRule> ROW_MAPPER = (rs, i) -> AlertRule.builder()
            .ruleId(rs.getString("rule_id"))
            .customerId(rs.getString("customer_id"))
            .name(rs.getString("name"))
            .description(rs.getString("description"))
            .eventType(rs.getString("event_type") != null ? EventType.valueOf(rs.getString("event_type")) : null)
            .riskThreshold(rs.getString("risk_threshold") != null ? RiskLevel.valueOf(rs.getString("risk_threshold")) : null)
            .conditionExpression(rs.getString("condition_expression"))
            .enabled(rs.getBoolean("enabled"))
            .notificationChannels(channels(rs.getString("notification_channels")))
            .build();

    private static final String COLUMNS = """
            rule_id, customer_id, name, description, event_type, risk_threshold,
            condition_expression, enabled, notification_channels""";

    private final JdbcTemplate jdbcTemplate;

    public AlertRuleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AlertRule> findAll(String customerId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM alert_rules WHERE customer_id = ? ORDER BY name",
                ROW_MAPPER, customerId);
    }

    public Optional<AlertRule> find(String customerId, String ruleId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM alert_rules WHERE customer_id = ? AND rule_id = ?",
                ROW_MAPPER, customerId, ruleId).stream().findFirst();
    }

    /** Insert or replace; the rule's own customerId is the scope. */
    public void upsert(AlertRule rule) {
        jdbcTemplate.update("""
                INSERT INTO alert_rules (rule_id, customer_id, name, description, event_type, risk_threshold,
                                         condition_expression, enabled, notification_channels)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (rule_id) DO UPDATE SET
                    name = EXCLUDED.name, description = EXCLUDED.description,
                    event_type = EXCLUDED.event_type, risk_threshold = EXCLUDED.risk_threshold,
                    condition_expression = EXCLUDED.condition_expression, enabled = EXCLUDED.enabled,
                    notification_channels = EXCLUDED.notification_channels
                WHERE alert_rules.customer_id = EXCLUDED.customer_id
                """,
                rule.getRuleId(), rule.getCustomerId(), rule.getName(), rule.getDescription(),
                rule.getEventType() != null ? rule.getEventType().name() : null,
                rule.getRiskThreshold() != null ? rule.getRiskThreshold().name() : null,
                rule.getConditionExpression(), rule.isEnabled(),
                String.join(",", rule.getNotificationChannels()));
    }

    /** @return true when a row of this customer was deleted */
    public boolean delete(String customerId, String ruleId) {
        return jdbcTemplate.update("DELETE FROM alert_rules WHERE customer_id = ? AND rule_id = ?", customerId, ruleId) == 1;
    }

    static List<String> channels(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(c -> !c.isEmpty()).toList();
    }
}
