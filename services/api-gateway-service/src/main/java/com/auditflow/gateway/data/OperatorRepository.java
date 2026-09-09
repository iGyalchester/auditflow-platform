package com.auditflow.gateway.data;

import com.auditflow.gateway.api.OperatorCustomer;
import com.auditflow.gateway.api.PlatformStats;
import com.auditflow.gateway.api.Stats;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The only queries in the gateway that are <em>not</em> scoped by a
 * customer: the platform operator's view across every tenant. Reachable
 * only through {@code /api/v1/operator/**}, which the security chain
 * restricts to {@code ROLE_OPERATOR}.
 */
@Repository
public class OperatorRepository {

    private final JdbcTemplate jdbcTemplate;

    public OperatorRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Every tenant anything mentions, busiest first. A tenant that was
     * never registered in {@code customers} still shows up (with a null
     * name) the moment a source pushes an event for it.
     */
    public List<OperatorCustomer> customers(Instant now) {
        Timestamp dayAgo = Timestamp.from(now.minus(Duration.ofDays(1)));
        Timestamp weekAgo = Timestamp.from(now.minus(Duration.ofDays(7)));
        return jdbcTemplate.query("""
                WITH ids AS (
                    SELECT customer_id FROM customers
                    UNION SELECT customer_id FROM audit_events
                    UNION SELECT customer_id FROM alert_rules
                ),
                ev AS (
                    SELECT customer_id,
                           count(*) FILTER (WHERE occurred_at >= ?) AS events_24h,
                           count(*) FILTER (WHERE occurred_at >= ?) AS events_7d,
                           max(occurred_at) AS last_event_at
                    FROM audit_events GROUP BY customer_id
                ),
                al AS (
                    SELECT customer_id, count(*) AS alerts_7d FROM alert_history
                    WHERE triggered_at >= ? GROUP BY customer_id
                ),
                ru AS (
                    SELECT customer_id, count(*) AS rules FROM alert_rules GROUP BY customer_id
                )
                SELECT i.customer_id, c.name,
                       coalesce(ev.events_24h, 0) AS events_24h, coalesce(ev.events_7d, 0) AS events_7d,
                       coalesce(al.alerts_7d, 0) AS alerts_7d, coalesce(ru.rules, 0) AS rules,
                       ev.last_event_at
                FROM ids i
                LEFT JOIN customers c ON c.customer_id = i.customer_id
                LEFT JOIN ev ON ev.customer_id = i.customer_id
                LEFT JOIN al ON al.customer_id = i.customer_id
                LEFT JOIN ru ON ru.customer_id = i.customer_id
                ORDER BY events_7d DESC, i.customer_id""",
                (rs, i) -> new OperatorCustomer(rs.getString("customer_id"), rs.getString("name"),
                        rs.getLong("events_24h"), rs.getLong("events_7d"), rs.getLong("alerts_7d"),
                        rs.getLong("rules"),
                        rs.getTimestamp("last_event_at") == null ? null : rs.getTimestamp("last_event_at").toInstant()),
                dayAgo, weekAgo, weekAgo);
    }

    public PlatformStats stats(Instant from, Instant to) {
        Timestamp start = Timestamp.from(from);
        Timestamp end = Timestamp.from(to);

        Map<String, String> names = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT customer_id, name FROM customers", rs -> {
            names.put(rs.getString("customer_id"), rs.getString("name"));
        });

        Map<LocalDate, Map<String, Long>> eventsByDay = new TreeMap<>();
        Map<String, Long> eventsByCustomer = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT (occurred_at AT TIME ZONE 'UTC')::date AS day, customer_id, count(*) AS n
                FROM audit_events WHERE occurred_at >= ? AND occurred_at < ?
                GROUP BY day, customer_id""",
                rs -> {
                    String customer = rs.getString("customer_id");
                    long n = rs.getLong("n");
                    eventsByDay.computeIfAbsent(rs.getDate("day").toLocalDate(), d -> new LinkedHashMap<>())
                            .merge(customer, n, Long::sum);
                    eventsByCustomer.merge(customer, n, Long::sum);
                }, start, end);
        Map<LocalDate, Long> alertsByDay = new TreeMap<>();
        Map<String, Long> alertsByCustomer = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT (triggered_at AT TIME ZONE 'UTC')::date AS day, customer_id, count(*) AS n
                FROM alert_history WHERE triggered_at >= ? AND triggered_at < ?
                GROUP BY day, customer_id""",
                rs -> {
                    long n = rs.getLong("n");
                    alertsByDay.merge(rs.getDate("day").toLocalDate(), n, Long::sum);
                    alertsByCustomer.merge(rs.getString("customer_id"), n, Long::sum);
                }, start, end);

        List<PlatformStats.DayBucket> perDay = new ArrayList<>();
        for (LocalDate day : StatsRepository.days(from, to)) {
            Map<String, Long> byCustomer = eventsByDay.getOrDefault(day, Map.of());
            long events = byCustomer.values().stream().mapToLong(Long::longValue).sum();
            perDay.add(new PlatformStats.DayBucket(day, events, alertsByDay.getOrDefault(day, 0L), byCustomer));
        }
        List<PlatformStats.CustomerCount> ranked = eventsByCustomer.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(e -> new PlatformStats.CustomerCount(e.getKey(), names.get(e.getKey()), e.getValue(),
                        alertsByCustomer.getOrDefault(e.getKey(), 0L)))
                .toList();
        long totalEvents = eventsByCustomer.values().stream().mapToLong(Long::longValue).sum();
        long totalAlerts = alertsByCustomer.values().stream().mapToLong(Long::longValue).sum();
        Map<String, String> legend = new LinkedHashMap<>();
        for (String id : eventsByCustomer.keySet()) {
            legend.put(id, names.get(id));
        }
        return new PlatformStats(new Stats.Window(from, to),
                new PlatformStats.Totals(totalEvents, totalAlerts, eventsByCustomer.size()),
                perDay, ranked, legend);
    }
}
