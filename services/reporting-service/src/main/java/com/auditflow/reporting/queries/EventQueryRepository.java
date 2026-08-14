package com.auditflow.reporting.queries;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.model.AuditEvent;
import com.auditflow.common.model.ComplianceControl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads a customer's audit events (with the controls each is evidence for)
 * from the Postgres metadata store for report generation. The S3/Athena
 * evidence-lake path ({@link AthenaQueryBuilder}) remains the deferred
 * large-scale alternative; reports source from Postgres today.
 */
@Repository
public class EventQueryRepository {

    private static final String SELECT_EVENTS = """
            SELECT event_id, customer_id, user_id, session_id, occurred_at,
                   event_type, resource, action, risk_level, anomalous
            FROM audit_events
            WHERE customer_id = ? AND occurred_at BETWEEN ? AND ?
            ORDER BY occurred_at
            """;

    private static final String SELECT_CONTROLS = """
            SELECT c.event_id, c.control_id, c.framework, c.name
            FROM event_controls c
            JOIN audit_events e ON e.event_id = c.event_id
            WHERE e.customer_id = ? AND e.occurred_at BETWEEN ? AND ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public EventQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AuditEvent> findEvents(String customerId, Instant from, Instant to) {
        Timestamp fromTs = Timestamp.from(from);
        Timestamp toTs = Timestamp.from(to);

        Map<String, List<ComplianceControl>> controlsByEventId = new HashMap<>();
        jdbcTemplate.query(SELECT_CONTROLS, rs -> {
            controlsByEventId
                    .computeIfAbsent(rs.getString("event_id"), id -> new java.util.ArrayList<>())
                    .add(ComplianceControl.builder()
                            .controlId(rs.getString("control_id"))
                            .framework(rs.getString("framework"))
                            .name(rs.getString("name"))
                            .build());
        }, customerId, fromTs, toTs);

        return jdbcTemplate.query(SELECT_EVENTS, (rs, rowNum) -> {
            String eventType = rs.getString("event_type");
            String riskLevel = rs.getString("risk_level");
            return AuditEvent.builder()
                    .eventId(rs.getString("event_id"))
                    .customerId(rs.getString("customer_id"))
                    .userId(rs.getString("user_id"))
                    .sessionId(rs.getString("session_id"))
                    .timestamp(rs.getTimestamp("occurred_at").toInstant())
                    .type(eventType == null ? null : EventType.valueOf(eventType))
                    .resource(rs.getString("resource"))
                    .action(rs.getString("action"))
                    .riskLevel(riskLevel == null ? null : RiskLevel.valueOf(riskLevel))
                    .anomalous(rs.getBoolean("anomalous"))
                    .controls(controlsByEventId.getOrDefault(rs.getString("event_id"), List.of()))
                    .build();
        }, customerId, fromTs, toTs);
    }
}
