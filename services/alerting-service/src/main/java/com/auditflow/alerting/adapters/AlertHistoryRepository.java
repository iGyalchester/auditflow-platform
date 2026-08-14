package com.auditflow.alerting.adapters;

import com.auditflow.common.model.AlertRule;
import com.auditflow.common.model.AuditEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Records every triggered alert so it is queryable through the API gateway
 * and usable as evidence that alerting was operating.
 */
@Repository
public class AlertHistoryRepository {

    private static final String INSERT_SQL = """
            INSERT INTO alert_history
                (alert_id, rule_id, event_id, customer_id, notified_channels)
            VALUES (?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public AlertHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(AlertRule rule, AuditEvent event, List<String> notifiedChannels) {
        jdbcTemplate.update(INSERT_SQL,
                UUID.randomUUID().toString(),
                rule.getRuleId(),
                event.getEventId(),
                event.getCustomerId(),
                String.join(",", notifiedChannels));
    }
}
