package com.auditflow.gateway.data;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.model.AuditEvent;
import com.auditflow.common.model.ComplianceControls;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Queryable event metadata, always filtered by customer first. The
 * customer id is a parameter of every query, never a caller-supplied
 * filter, so cross-tenant reads are impossible by construction.
 */
@Repository
public class AuditLogRepository {

    public record AuditLogRow(String eventId, String userId, String sessionId, Instant occurredAt,
                              String eventType, String resource, String action, String riskLevel,
                              boolean anomalous, String controls) {
    }

    private final JdbcTemplate jdbcTemplate;

    public AuditLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AuditLogRow> find(String customerId, String eventType, Instant from, Instant to, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT event_id, user_id, session_id, occurred_at, event_type, resource, action,
                       risk_level, anomalous, controls
                FROM audit_events WHERE customer_id = ?""");
        List<Object> args = new ArrayList<>();
        args.add(customerId);
        if (eventType != null) {
            sql.append(" AND event_type = ?");
            args.add(eventType);
        }
        if (from != null) {
            sql.append(" AND occurred_at >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND occurred_at < ?");
            args.add(Timestamp.from(to));
        }
        sql.append(" ORDER BY occurred_at DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), (rs, i) -> new AuditLogRow(
                rs.getString("event_id"), rs.getString("user_id"), rs.getString("session_id"),
                rs.getTimestamp("occurred_at").toInstant(), rs.getString("event_type"),
                rs.getString("resource"), rs.getString("action"), rs.getString("risk_level"),
                rs.getBoolean("anomalous"), rs.getString("controls")), args.toArray());
    }

    /** Reports need domain events (with their controls) over a window, oldest first. */
    public List<AuditEvent> findForReport(String customerId, Instant from, Instant to, int maxRows) {
        return jdbcTemplate.query("""
                SELECT event_id, customer_id, user_id, session_id, occurred_at, event_type, resource, action,
                       risk_level, anomalous, controls
                FROM audit_events
                WHERE customer_id = ? AND occurred_at >= ? AND occurred_at < ?
                ORDER BY occurred_at
                LIMIT ?""", (rs, i) -> AuditEvent.builder()
                .eventId(rs.getString("event_id"))
                .customerId(rs.getString("customer_id"))
                .userId(rs.getString("user_id"))
                .sessionId(rs.getString("session_id"))
                .timestamp(rs.getTimestamp("occurred_at").toInstant())
                .type(rs.getString("event_type") != null ? EventType.valueOf(rs.getString("event_type")) : null)
                .resource(rs.getString("resource"))
                .action(rs.getString("action"))
                .riskLevel(rs.getString("risk_level") != null ? RiskLevel.valueOf(rs.getString("risk_level")) : null)
                .anomalous(rs.getBoolean("anomalous"))
                .controls(ComplianceControls.decode(rs.getString("controls")))
                .build(), customerId, Timestamp.from(from), Timestamp.from(to), maxRows);
    }
}
