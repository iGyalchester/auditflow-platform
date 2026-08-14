package com.auditflow.alerting.adapters;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.model.AlertRule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;

/**
 * Loads customer alert rules from the shared Aurora/Postgres store.
 * notification_channels is stored as a comma-separated list (e.g.
 * "slack,email"); empty/null means "all channels".
 */
@Repository
public class AlertRuleRepository {

    private static final String SELECT_ENABLED_BY_CUSTOMER = """
            SELECT rule_id, customer_id, name, event_type, risk_threshold,
                   condition_expression, enabled, notification_channels
            FROM alert_rules
            WHERE customer_id = ? AND enabled = true
            """;

    private static final RowMapper<AlertRule> ROW_MAPPER = (rs, rowNum) -> {
        String eventType = rs.getString("event_type");
        String riskThreshold = rs.getString("risk_threshold");
        String channels = rs.getString("notification_channels");
        return AlertRule.builder()
                .ruleId(rs.getString("rule_id"))
                .customerId(rs.getString("customer_id"))
                .name(rs.getString("name"))
                .eventType(eventType == null ? null : EventType.valueOf(eventType))
                .riskThreshold(riskThreshold == null ? null : RiskLevel.valueOf(riskThreshold))
                .conditionExpression(rs.getString("condition_expression"))
                .enabled(rs.getBoolean("enabled"))
                .notificationChannels(parseChannels(channels))
                .build();
    };

    private static List<String> parseChannels(String channels) {
        if (channels == null || channels.isBlank()) {
            return List.of();
        }
        return Arrays.stream(channels.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private final JdbcTemplate jdbcTemplate;

    public AlertRuleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AlertRule> findEnabledByCustomer(String customerId) {
        return jdbcTemplate.query(SELECT_ENABLED_BY_CUSTOMER, ROW_MAPPER, customerId);
    }
}
