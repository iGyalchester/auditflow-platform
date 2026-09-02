package com.auditflow.alerting.history;

import com.auditflow.common.model.AlertRule;
import com.auditflow.common.model.AuditEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The record of every alert that fired: which rule, which event, which
 * customer, and which channels actually got the message. This is what the
 * gateway's /api/v1/alerts lists, and the evidence that "we were notified"
 * an auditor asks for.
 */
@Component
public class AlertHistoryWriter {

    static final String INSERT_SQL = """
            INSERT INTO alert_history (alert_id, rule_id, event_id, customer_id, notified_channels)
            VALUES (?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public AlertHistoryWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** @return the new alert id */
    public String record(AlertRule rule, AuditEvent event, List<String> notifiedChannels) {
        String alertId = UUID.randomUUID().toString();
        jdbcTemplate.update(INSERT_SQL, alertId, rule.getRuleId(), event.getEventId(),
                event.getCustomerId(), String.join(",", notifiedChannels));
        return alertId;
    }
}
