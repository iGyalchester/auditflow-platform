package com.auditflow.gateway.data;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Alerts that fired for a customer, newest first, with the rule's name. */
@Repository
public class AlertHistoryRepository {

    /**
     * @param notifiedChannels the channels that were actually reached, comma-separated
     * @param ruleChannels     the channels the rule is configured with today (null when
     *                         the rule is gone); the difference is "configured but not delivered"
     */
    public record AlertRow(String alertId, String ruleId, String ruleName, String eventId,
                           Instant triggeredAt, String notifiedChannels, String ruleChannels) {
    }

    /** Optional narrowing of the feed. */
    public record AlertFilter(String ruleId, Instant from, Instant to) {

        public static final AlertFilter NONE = new AlertFilter(null, null, null);
    }

    static final String SELECT = """
            SELECT h.alert_id, h.rule_id, r.name AS rule_name, h.event_id, h.triggered_at, h.notified_channels,
                   r.notification_channels AS rule_channels
            FROM alert_history h
            LEFT JOIN alert_rules r ON r.rule_id = h.rule_id
            WHERE h.customer_id = ?""";

    static final RowMapper<AlertRow> MAPPER = (rs, i) -> new AlertRow(
            rs.getString("alert_id"), rs.getString("rule_id"), rs.getString("rule_name"),
            rs.getString("event_id"), rs.getTimestamp("triggered_at").toInstant(),
            rs.getString("notified_channels"), rs.getString("rule_channels"));

    private final JdbcTemplate jdbcTemplate;

    public AlertHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AlertRow> find(String customerId, AlertFilter filter, int limit) {
        StringBuilder sql = new StringBuilder(SELECT);
        List<Object> args = new ArrayList<>();
        args.add(customerId);
        if (filter.ruleId() != null) {
            sql.append(" AND h.rule_id = ?");
            args.add(filter.ruleId());
        }
        if (filter.from() != null) {
            sql.append(" AND h.triggered_at >= ?");
            args.add(Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND h.triggered_at < ?");
            args.add(Timestamp.from(filter.to()));
        }
        sql.append(" ORDER BY h.triggered_at DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), MAPPER, args.toArray());
    }

    public Optional<AlertRow> findOne(String customerId, String alertId) {
        return jdbcTemplate.query(SELECT + " AND h.alert_id = ?", MAPPER, customerId, alertId).stream().findFirst();
    }

    /** The alerts one event raised, newest first. */
    public List<AlertRow> findForEvent(String customerId, String eventId) {
        return jdbcTemplate.query(SELECT + " AND h.event_id = ? ORDER BY h.triggered_at DESC", MAPPER, customerId, eventId);
    }
}
