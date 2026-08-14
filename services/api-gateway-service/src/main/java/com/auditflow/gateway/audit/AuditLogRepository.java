package com.auditflow.gateway.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Reads audit event metadata from Aurora/Postgres, always scoped to a single
 * customer per the multi-tenant-from-day-1 principle - there is deliberately
 * no "list everything" query.
 */
@Repository
public class AuditLogRepository {

    private static final String SELECT_BY_CUSTOMER = """
            SELECT event_id, customer_id, user_id, session_id, occurred_at,
                   event_type, resource, action, risk_level, anomalous
            FROM audit_events
            WHERE customer_id = ?
            ORDER BY occurred_at DESC
            LIMIT ?
            """;

    private static final RowMapper<AuditLogEntry> ROW_MAPPER = (rs, rowNum) -> new AuditLogEntry(
            rs.getString("event_id"),
            rs.getString("customer_id"),
            rs.getString("user_id"),
            rs.getString("session_id"),
            rs.getTimestamp("occurred_at").toInstant(),
            rs.getString("event_type"),
            rs.getString("resource"),
            rs.getString("action"),
            rs.getString("risk_level"),
            rs.getBoolean("anomalous"));

    private final JdbcTemplate jdbcTemplate;

    public AuditLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AuditLogEntry> findByCustomer(String customerId, int limit) {
        return jdbcTemplate.query(SELECT_BY_CUSTOMER, ROW_MAPPER, customerId, limit);
    }
}
