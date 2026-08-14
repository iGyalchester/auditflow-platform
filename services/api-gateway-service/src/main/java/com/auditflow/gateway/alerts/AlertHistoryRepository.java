package com.auditflow.gateway.alerts;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Reads triggered alerts from Aurora/Postgres, always scoped to a single
 * customer per the multi-tenant-from-day-1 principle.
 */
@Repository
public class AlertHistoryRepository {

    private static final String SELECT_BY_CUSTOMER = """
            SELECT h.alert_id, h.rule_id, r.name AS rule_name, h.event_id,
                   h.customer_id, h.triggered_at, h.notified_channels
            FROM alert_history h
            LEFT JOIN alert_rules r ON r.rule_id = h.rule_id
            WHERE h.customer_id = ?
            ORDER BY h.triggered_at DESC
            LIMIT ?
            """;

    private static final RowMapper<AlertHistoryEntry> ROW_MAPPER = (rs, rowNum) -> new AlertHistoryEntry(
            rs.getString("alert_id"),
            rs.getString("rule_id"),
            rs.getString("rule_name"),
            rs.getString("event_id"),
            rs.getString("customer_id"),
            rs.getTimestamp("triggered_at").toInstant(),
            rs.getString("notified_channels"));

    private final JdbcTemplate jdbcTemplate;

    public AlertHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AlertHistoryEntry> findByCustomer(String customerId, int limit) {
        return jdbcTemplate.query(SELECT_BY_CUSTOMER, ROW_MAPPER, customerId, limit);
    }
}
