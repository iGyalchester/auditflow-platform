package com.auditflow.gateway.data;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** Alerts that fired for a customer, newest first, with the rule's name. */
@Repository
public class AlertHistoryRepository {

    public record AlertRow(String alertId, String ruleId, String ruleName, String eventId,
                           Instant triggeredAt, String notifiedChannels) {
    }

    static final String SQL = """
            SELECT h.alert_id, h.rule_id, r.name AS rule_name, h.event_id, h.triggered_at, h.notified_channels
            FROM alert_history h
            LEFT JOIN alert_rules r ON r.rule_id = h.rule_id
            WHERE h.customer_id = ?
            ORDER BY h.triggered_at DESC
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public AlertHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AlertRow> find(String customerId, int limit) {
        return jdbcTemplate.query(SQL, (rs, i) -> new AlertRow(
                rs.getString("alert_id"), rs.getString("rule_id"), rs.getString("rule_name"),
                rs.getString("event_id"), rs.getTimestamp("triggered_at").toInstant(),
                rs.getString("notified_channels")), customerId, limit);
    }
}
