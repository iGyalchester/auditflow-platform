package com.auditflow.gateway.data;

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
}
