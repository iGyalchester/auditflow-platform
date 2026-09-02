package com.auditflow.agent.collector;

import com.auditflow.agent.redact.QueryRedactor;
import com.auditflow.common.enums.EventType;
import com.auditflow.common.model.AuditEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Tails MySQL's general query log (requires {@code log_output=TABLE} and
 * {@code general_log=ON} on the source database) and normalizes each
 * statement into an {@link AuditEvent}.
 *
 * <p>Security posture:
 * <ul>
 *   <li>every statement is redacted ({@link QueryRedactor}) before it
 *       leaves this process - literals never reach the platform;</li>
 *   <li>rawLog is deliberately never populated for the same reason;</li>
 *   <li>event ids are a deterministic hash of (time, thread, statement),
 *       so re-reading after a restart cannot double-count - the Aurora
 *       sink's ON CONFLICT DO NOTHING makes replays a no-op;</li>
 *   <li>run it with a dedicated read-only MySQL account (SELECT on
 *       mysql.general_log only) - the agent never needs to write.</li>
 * </ul>
 */
@Component
public class MySqlGeneralLogCollector implements CommittableCollector {

    private static final String QUERY = """
            SELECT event_time, user_host, thread_id, command_type, argument
            FROM mysql.general_log
            WHERE event_time > ? AND command_type IN ('Query', 'Execute')
            ORDER BY event_time
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final String customerId;
    private final String resourceName;
    private final int batchSize;

    // committed read position; only advances via commit() after the runner
    // confirms delivery, so a failed publish is re-read next poll (dedupe
    // downstream makes the overlap harmless)
    private volatile Instant checkpoint = Instant.now();
    private volatile Instant pendingCheckpoint = checkpoint;

    public MySqlGeneralLogCollector(JdbcTemplate jdbcTemplate,
                                    @Value("${agent.customer-id}") String customerId,
                                    @Value("${agent.resource-name:mysql}") String resourceName,
                                    @Value("${agent.batch-size:500}") int batchSize) {
        this.jdbcTemplate = jdbcTemplate;
        this.customerId = customerId;
        this.resourceName = resourceName;
        this.batchSize = batchSize;
    }

    @Override
    public List<AuditEvent> collect() {
        Instant since = checkpoint;
        List<AuditEvent> events = jdbcTemplate.query(QUERY, (rs, rowNum) -> {
                    Timestamp eventTime = rs.getTimestamp("event_time");
                    byte[] argumentBytes = rs.getBytes("argument");
                    String argument = argumentBytes == null ? "" : new String(argumentBytes, StandardCharsets.UTF_8);
                    return toEvent(eventTime.toInstant(), rs.getString("user_host"),
                            rs.getLong("thread_id"), argument);
                }, Timestamp.from(since), batchSize).stream()
                .filter(Objects::nonNull)
                .toList();

        jdbcTemplate.query("SELECT MAX(event_time) FROM mysql.general_log", rs -> {
            Timestamp max = rs.getTimestamp(1);
            if (max != null && max.toInstant().isAfter(since)) {
                pendingCheckpoint = max.toInstant();
            }
        });
        return events;
    }

    @Override
    public void commit() {
        checkpoint = pendingCheckpoint;
    }

    @Override
    public String sourceName() {
        return "mysql-general-log:" + resourceName;
    }

    /** Package-private so the mapping/filtering rules are unit-testable without a DB. */
    AuditEvent toEvent(Instant eventTime, String userHost, long threadId, String statement) {
        String trimmed = statement == null ? "" : statement.trim();
        if (isNoise(trimmed)) {
            return null;
        }
        return AuditEvent.builder()
                .eventId(deterministicId(eventTime, threadId, trimmed))
                .customerId(customerId)
                .userId(parseUser(userHost))
                .timestamp(eventTime)
                .type(EventType.DATABASE_QUERY)
                .resource(resourceName)
                .action(firstKeyword(trimmed))
                .query(QueryRedactor.redact(trimmed))
                .build();
    }

    /**
     * Skips statements that would make the trail self-referential or
     * worthless: the agent's own polling, system-schema traffic, and
     * connection chatter (SET/SHOW/USE and driver @@ probes).
     */
    private boolean isNoise(String statement) {
        if (statement.isEmpty()) {
            return true;
        }
        String lower = statement.toLowerCase(Locale.ROOT);
        return lower.contains("general_log")
                || lower.contains("information_schema")
                || lower.contains("performance_schema")
                || lower.startsWith("set ")
                || lower.startsWith("show ")
                || lower.startsWith("use ")
                || lower.contains("@@");
    }

    // "boris[boris] @ localhost [127.0.0.1]" -> "boris"
    private static String parseUser(String userHost) {
        if (userHost == null || userHost.isBlank()) {
            return "unknown";
        }
        int bracket = userHost.indexOf('[');
        String user = bracket > 0 ? userHost.substring(0, bracket) : userHost;
        return user.isBlank() ? "unknown" : user.trim();
    }

    private static String firstKeyword(String statement) {
        int space = statement.indexOf(' ');
        String keyword = space > 0 ? statement.substring(0, space) : statement;
        return keyword.toUpperCase(Locale.ROOT);
    }

    private static String deterministicId(Instant eventTime, long threadId, String statement) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((eventTime.toString() + '|' + threadId + '|' + statement)
                    .getBytes(StandardCharsets.UTF_8));
            return "mysql-" + HexFormat.of().formatHex(digest.digest(), 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
