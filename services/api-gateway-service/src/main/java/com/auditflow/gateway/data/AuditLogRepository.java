package com.auditflow.gateway.data;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.model.AuditEvent;
import com.auditflow.common.model.ComplianceControls;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    /**
     * The explorer's filters. Every field is optional; {@code q} is a
     * case-insensitive substring of the resource or the action. Paging is
     * keyset: the client passes the oldest {@code occurredAt} it has seen
     * as {@code to} to load the page before it.
     */
    public record AuditLogFilter(String eventType, String riskLevel, String userId, Boolean anomalous,
                                 String q, Instant from, Instant to) {

        public static final AuditLogFilter NONE = new AuditLogFilter(null, null, null, null, null, null, null);

        public static AuditLogFilter window(Instant from, Instant to) {
            return new AuditLogFilter(null, null, null, null, null, from, to);
        }
    }

    static final String ROW_COLUMNS = """
            event_id, user_id, session_id, occurred_at, event_type, resource, action,
            risk_level, anomalous, controls""";

    static final RowMapper<AuditLogRow> ROW_MAPPER = (rs, i) -> new AuditLogRow(
            rs.getString("event_id"), rs.getString("user_id"), rs.getString("session_id"),
            rs.getTimestamp("occurred_at").toInstant(), rs.getString("event_type"),
            rs.getString("resource"), rs.getString("action"), rs.getString("risk_level"),
            rs.getBoolean("anomalous"), rs.getString("controls"));

    /** The domain object, for code that evaluates rules or builds reports. */
    static final RowMapper<AuditEvent> EVENT_MAPPER = (rs, i) -> AuditEvent.builder()
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
            .build();

    private final JdbcTemplate jdbcTemplate;

    public AuditLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AuditLogRow> find(String customerId, AuditLogFilter filter, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + ROW_COLUMNS + " FROM audit_events WHERE customer_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(customerId);
        appendFilter(sql, args, filter);
        sql.append(" ORDER BY occurred_at DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    public Optional<AuditLogRow> findOne(String customerId, String eventId) {
        return jdbcTemplate.query("SELECT " + ROW_COLUMNS + " FROM audit_events WHERE customer_id = ? AND event_id = ?",
                ROW_MAPPER, customerId, eventId).stream().findFirst();
    }

    /**
     * Domain events in a window, oldest first, capped, optionally narrowed
     * to a rule's cheap criteria - the event type and "risk at or above" -
     * so the dry run only materialises the rows its condition has to look
     * at. The condition itself (SpEL) cannot run in SQL.
     */
    public List<AuditEvent> findEvents(String customerId, Instant from, Instant to,
                                       EventType eventType, RiskLevel riskAtLeast, int maxRows) {
        StringBuilder sql = new StringBuilder("""
                SELECT event_id, customer_id, user_id, session_id, occurred_at, event_type, resource, action,
                       risk_level, anomalous, controls
                FROM audit_events
                WHERE customer_id = ? AND occurred_at >= ? AND occurred_at < ?""");
        List<Object> args = new ArrayList<>(List.of(customerId, Timestamp.from(from), Timestamp.from(to)));
        appendCriteria(sql, args, eventType, riskAtLeast);
        sql.append(" ORDER BY occurred_at LIMIT ?");
        args.add(maxRows);
        return jdbcTemplate.query(sql.toString(), EVENT_MAPPER, args.toArray());
    }

    /** How many events in the window meet the cheap criteria (all of them when both are null). */
    public long countEvents(String customerId, Instant from, Instant to, EventType eventType, RiskLevel riskAtLeast) {
        StringBuilder sql = new StringBuilder(
                "SELECT count(*) FROM audit_events WHERE customer_id = ? AND occurred_at >= ? AND occurred_at < ?");
        List<Object> args = new ArrayList<>(List.of(customerId, Timestamp.from(from), Timestamp.from(to)));
        appendCriteria(sql, args, eventType, riskAtLeast);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    /** {@code risk_level IN (...)} lists the levels at or above the threshold, the same order RuleMatcher ranks them. */
    static void appendCriteria(StringBuilder sql, List<Object> args, EventType eventType, RiskLevel riskAtLeast) {
        if (eventType != null) {
            sql.append(" AND event_type = ?");
            args.add(eventType.name());
        }
        if (riskAtLeast != null) {
            List<String> levels = new ArrayList<>();
            for (RiskLevel level : RiskLevel.values()) {
                if (level.ordinal() >= riskAtLeast.ordinal()) {
                    levels.add(level.name());
                }
            }
            sql.append(" AND risk_level IN (").append("?, ".repeat(levels.size() - 1)).append("?)");
            args.addAll(levels);
        }
    }

    /**
     * Domain events for one framework's report, over a window, oldest first.
     *
     * <p>The framework filter is here rather than in the generator because
     * the caller has to cap the result, and a cap only means something if it
     * counts the rows the report will actually contain. Loading the whole
     * window and filtering in Java meant a tenant with 10,001 events and 50
     * SOC 2 events got a 413 for a report that would have been 50 lines
     * long.
     *
     * <p>{@code controls} is {@code FRAMEWORK:CONTROL,FRAMEWORK:CONTROL},
     * so a framework is either at the start or after a comma. Both patterns
     * are bound as parameters. The generators keep their own filter as a
     * guard - this narrows what is loaded, it does not become the only place
     * the rule is written down.
     */
    public List<AuditEvent> findForReport(String customerId, String framework,
                                          Instant from, Instant to, int maxRows) {
        return jdbcTemplate.query("""
                SELECT event_id, customer_id, user_id, session_id, occurred_at, event_type, resource, action,
                       risk_level, anomalous, controls
                FROM audit_events
                WHERE customer_id = ? AND occurred_at >= ? AND occurred_at < ?
                  AND (controls LIKE ? OR controls LIKE ?)
                ORDER BY occurred_at
                LIMIT ?""", EVENT_MAPPER, customerId, Timestamp.from(from), Timestamp.from(to),
                framework + ":%", "%," + framework + ":%", maxRows);
    }

    static void appendFilter(StringBuilder sql, List<Object> args, AuditLogFilter filter) {
        if (filter.eventType() != null) {
            sql.append(" AND event_type = ?");
            args.add(filter.eventType());
        }
        if (filter.riskLevel() != null) {
            sql.append(" AND risk_level = ?");
            args.add(filter.riskLevel());
        }
        if (filter.userId() != null) {
            sql.append(" AND user_id = ?");
            args.add(filter.userId());
        }
        if (filter.anomalous() != null) {
            sql.append(" AND anomalous = ?");
            args.add(filter.anomalous());
        }
        if (filter.q() != null && !filter.q().isBlank()) {
            // ILIKE with the wildcards escaped: a "%" typed into the search
            // box is a character to find, not a pattern to widen
            String pattern = "%" + filter.q().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            sql.append(" AND (resource ILIKE ? OR action ILIKE ?)");
            args.add(pattern);
            args.add(pattern);
        }
        if (filter.from() != null) {
            sql.append(" AND occurred_at >= ?");
            args.add(Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND occurred_at < ?");
            args.add(Timestamp.from(filter.to()));
        }
    }
}
