package com.auditflow.alerting.history;

import com.auditflow.common.model.AlertRule;
import com.auditflow.common.model.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The record of every alert that fired: which rule, which event, which
 * customer, and which channels actually got the message. This is what the
 * gateway's /api/v1/alerts lists, and the evidence that "we were notified"
 * an auditor asks for.
 *
 * <p>Note the subselect on rule_id rather than the value itself. Alerting
 * reloads rules on a timer, so for up to one refresh interval it can still
 * match a rule the API has already deleted. Writing that id straight in
 * would violate the foreign key and lose the record of an alert that really
 * did fire; resolving it through the table stores NULL instead, and the
 * gateway's LEFT JOIN already renders an unattributed alert.
 */
@Component
public class AlertHistoryWriter {

    private static final Logger log = LoggerFactory.getLogger(AlertHistoryWriter.class);

    static final String INSERT_SQL = """
            INSERT INTO alert_history (alert_id, rule_id, event_id, customer_id, notified_channels)
            VALUES (?, (SELECT rule_id FROM alert_rules WHERE rule_id = ?), ?, ?, ?)
            """;

    static final String INSERT_UNATTRIBUTED_SQL = """
            INSERT INTO alert_history (alert_id, rule_id, event_id, customer_id, notified_channels)
            VALUES (?, NULL, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public AlertHistoryWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** @return the new alert id */
    public String record(AlertRule rule, AuditEvent event, List<String> notifiedChannels) {
        String alertId = UUID.randomUUID().toString();
        String channels = String.join(",", notifiedChannels);
        try {
            jdbcTemplate.update(INSERT_SQL, alertId, rule.getRuleId(), event.getEventId(),
                    event.getCustomerId(), channels);
        } catch (DataIntegrityViolationException e) {
            // The rule was deleted between the subselect and the insert.
            // Recording the alert without attribution beats losing it.
            log.warn("Rule '{}' disappeared while recording alert {}; storing it unattributed",
                    rule.getRuleId(), alertId);
            jdbcTemplate.update(INSERT_UNATTRIBUTED_SQL, alertId, event.getEventId(),
                    event.getCustomerId(), channels);
        }
        return alertId;
    }
}
