package com.auditflow.gateway.data;

import com.auditflow.common.enums.RiskLevel;
import com.auditflow.gateway.api.Stats;
import com.auditflow.gateway.controllers.TimeWindow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The dashboard's numbers, computed in SQL and scoped by customer like
 * every other query here. Days are UTC days: the console shows the same
 * chart to everyone, and the source clocks that stamped the events are UTC
 * too. Every per-day series is zero-filled in Java so a quiet day is a
 * zero on the chart rather than a missing bar.
 */
@Repository
public class StatsRepository {

    private static final int TOP_N = 10;

    private final JdbcTemplate jdbcTemplate;

    public StatsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Stats stats(String customerId, TimeWindow window) {
        Instant from = window.from();
        Instant to = window.to();
        TimeWindow previous = window.previous();
        return new Stats(
                new Stats.Window(from, to),
                totals(customerId, from, to),
                totals(customerId, previous.from(), previous.to()),
                perDay(customerId, from, to),
                countBy(customerId, from, to, "event_type"),
                withEveryRisk(countBy(customerId, from, to, "risk_level")),
                byControl(customerId, from, to),
                top(customerId, from, to, "user_id"),
                top(customerId, from, to, "resource"));
    }

    Stats.Totals totals(String customerId, Instant from, Instant to) {
        Long alerts = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM alert_history
                WHERE customer_id = ? AND triggered_at >= ? AND triggered_at < ?""",
                Long.class, customerId, Timestamp.from(from), Timestamp.from(to));
        long alertCount = alerts == null ? 0 : alerts;
        return jdbcTemplate.queryForObject("""
                SELECT count(*) AS events,
                       count(*) FILTER (WHERE risk_level = 'CRITICAL') AS critical,
                       count(*) FILTER (WHERE anomalous) AS anomalous,
                       count(DISTINCT user_id) AS users
                FROM audit_events
                WHERE customer_id = ? AND occurred_at >= ? AND occurred_at < ?""",
                (rs, i) -> new Stats.Totals(rs.getLong("events"), alertCount, rs.getLong("critical"),
                        rs.getLong("anomalous"), rs.getLong("users")),
                customerId, Timestamp.from(from), Timestamp.from(to));
    }

    List<Stats.DayBucket> perDay(String customerId, Instant from, Instant to) {
        // day -> risk -> count; TreeMap keeps the days in order
        Map<LocalDate, Map<String, Long>> eventsByDay = new TreeMap<>();
        jdbcTemplate.query("""
                SELECT (occurred_at AT TIME ZONE 'UTC')::date AS day, risk_level, count(*) AS n
                FROM audit_events
                WHERE customer_id = ? AND occurred_at >= ? AND occurred_at < ?
                GROUP BY day, risk_level""",
                rs -> {
                    LocalDate day = rs.getDate("day").toLocalDate();
                    String risk = rs.getString("risk_level");
                    eventsByDay.computeIfAbsent(day, d -> new LinkedHashMap<>())
                            .merge(risk == null ? "UNKNOWN" : risk, rs.getLong("n"), Long::sum);
                }, customerId, Timestamp.from(from), Timestamp.from(to));
        Map<LocalDate, Long> alertsByDay = new TreeMap<>();
        jdbcTemplate.query("""
                SELECT (triggered_at AT TIME ZONE 'UTC')::date AS day, count(*) AS n
                FROM alert_history
                WHERE customer_id = ? AND triggered_at >= ? AND triggered_at < ?
                GROUP BY day""",
                rs -> {
                    alertsByDay.put(rs.getDate("day").toLocalDate(), rs.getLong("n"));
                }, customerId, Timestamp.from(from), Timestamp.from(to));

        List<Stats.DayBucket> buckets = new ArrayList<>();
        for (LocalDate day : days(from, to)) {
            Map<String, Long> byRisk = withEveryRisk(eventsByDay.getOrDefault(day, Map.of()));
            long events = byRisk.values().stream().mapToLong(Long::longValue).sum();
            buckets.add(new Stats.DayBucket(day, events, alertsByDay.getOrDefault(day, 0L), byRisk));
        }
        return buckets;
    }

    /** Every UTC day the window touches, oldest first. */
    static List<LocalDate> days(Instant from, Instant to) {
        List<LocalDate> days = new ArrayList<>();
        LocalDate day = from.atZone(ZoneOffset.UTC).toLocalDate();
        // "to" is exclusive: a window ending exactly at midnight does not include that day
        LocalDate last = to.minusNanos(1).atZone(ZoneOffset.UTC).toLocalDate();
        while (!day.isAfter(last)) {
            days.add(day);
            day = day.plusDays(1);
        }
        return days;
    }

    /** Only these two columns are ever grouped on; the name is code, not input. */
    Map<String, Long> countBy(String customerId, Instant from, Instant to, String column) {
        if (!column.equals("event_type") && !column.equals("risk_level")) {
            throw new IllegalArgumentException(column);
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT " + column + " AS k, count(*) AS n FROM audit_events "
                        + "WHERE customer_id = ? AND occurred_at >= ? AND occurred_at < ? AND " + column
                        + " IS NOT NULL GROUP BY k ORDER BY n DESC, k",
                rs -> {
                    counts.put(rs.getString("k"), rs.getLong("n"));
                }, customerId, Timestamp.from(from), Timestamp.from(to));
        return counts;
    }

    /** {@code FRAMEWORK:CONTROL} pairs are stored comma-joined; unnest splits them back out. */
    Map<String, Long> byControl(String customerId, Instant from, Instant to) {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT trim(c) AS control, count(*) AS n
                FROM audit_events, unnest(string_to_array(controls, ',')) AS c
                WHERE customer_id = ? AND occurred_at >= ? AND occurred_at < ? AND controls IS NOT NULL
                GROUP BY control ORDER BY n DESC, control""",
                rs -> {
                    counts.put(rs.getString("control"), rs.getLong("n"));
                }, customerId, Timestamp.from(from), Timestamp.from(to));
        return counts;
    }

    List<Stats.NameCount> top(String customerId, Instant from, Instant to, String column) {
        if (!column.equals("user_id") && !column.equals("resource")) {
            throw new IllegalArgumentException(column);
        }
        return jdbcTemplate.query("SELECT " + column + " AS k, count(*) AS n FROM audit_events "
                        + "WHERE customer_id = ? AND occurred_at >= ? AND occurred_at < ? AND " + column
                        + " IS NOT NULL GROUP BY k ORDER BY n DESC, k LIMIT ?",
                (rs, i) -> new Stats.NameCount(rs.getString("k"), rs.getLong("n")),
                customerId, Timestamp.from(from), Timestamp.from(to), TOP_N);
    }

    /** LOW..CRITICAL always present, in order, so the chart's stack never changes shape. */
    static Map<String, Long> withEveryRisk(Map<String, Long> counts) {
        Map<String, Long> full = new LinkedHashMap<>();
        for (RiskLevel level : RiskLevel.values()) {
            full.put(level.name(), counts.getOrDefault(level.name(), 0L));
        }
        counts.forEach((k, v) -> full.putIfAbsent(k, v));
        return full;
    }
}
