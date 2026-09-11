package com.auditflow.agent.collector;

import com.auditflow.agent.redact.QueryRedactor;
import com.auditflow.common.enums.EventType;
import com.auditflow.common.model.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Locale;
import java.util.Set;

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
 *
 * <h2>Why the cursor is a timestamp AND a set of ids</h2>
 *
 * <p>The obvious checkpoint - "the newest event_time I have seen" - loses
 * rows in two different ways, and this class exists to avoid both.
 *
 * <p><b>Skipping past unread rows.</b> A batch is capped at
 * {@code batch-size}, so a backlog is drained over several polls. Taking
 * the checkpoint from {@code SELECT MAX(event_time)} over the whole table -
 * which is what this did - moves it past rows the batch never returned, and
 * they are never read again. On a busy database that is silent, permanent
 * loss of exactly the evidence the agent exists to collect. The cursor now
 * advances only to the last row actually returned.
 *
 * <p><b>Losing rows that share a timestamp.</b> general_log timestamps have
 * microsecond resolution and a busy server writes several rows inside one.
 * A strictly-greater-than cursor skips the rest of that microsecond; a
 * greater-or-equal one re-reads them forever. So the cursor also carries
 * the ids already seen at its boundary timestamp: the query is {@code >=}
 * and those ids are filtered out, which is exact rather than approximate in
 * either direction.
 */
@Component
public class MySqlGeneralLogCollector implements CommittableCollector {

    private static final Logger log = LoggerFactory.getLogger(MySqlGeneralLogCollector.class);

    // Keyset pagination on (event_time, thread_id). ">= time" alone cannot
    // drain a timestamp holding more rows than one batch: the query keeps
    // returning the same first N and the cursor never moves.
    private static final String QUERY = """
            SELECT event_time, user_host, thread_id, command_type, argument
            FROM mysql.general_log
            WHERE (event_time > ? OR (event_time = ? AND thread_id >= ?))
              AND command_type IN ('Query', 'Execute')
            ORDER BY event_time, thread_id
            LIMIT ?
            """;

    /** One log row, before any decision about whether it is worth reporting. */
    record RawRow(Instant eventTime, String userHost, long threadId, String statement) {
    }

    /**
     * Read position, as a keyset: everything before ({@code since},
     * {@code sinceThreadId}) is done, plus the ids listed here at exactly
     * that pair.
     *
     * <p>That id set is bounded by how many statements one thread logged in
     * a single microsecond - one or two in practice - because the thread id
     * carries the rest of the ordering.
     */
    record Cursor(Instant since, long sinceThreadId, Set<String> seenAtBoundary) {
    }

    /** The database, behind a seam so the cursor logic is testable without one. */
    interface RowReader {
        List<RawRow> read(Instant since, long sinceThreadId, int limit);
    }

    private final RowReader rowReader;
    private final String customerId;
    private final String resourceName;
    private final int batchSize;

    // committed read position; only advances via commit() after the runner
    // confirms delivery, so a failed publish is re-read next poll (dedupe
    // downstream makes the overlap harmless)
    private volatile Cursor checkpoint;
    private volatile Cursor pending;

    // null when no checkpoint file is configured: the cursor then lives only
    // in memory, which is the pre-existing behaviour and fine for a test or
    // a throwaway run
    private final CheckpointStore checkpointStore;

    @Autowired
    public MySqlGeneralLogCollector(JdbcTemplate jdbcTemplate,
                                    @Value("${agent.customer-id}") String customerId,
                                    @Value("${agent.resource-name:mysql}") String resourceName,
                                    @Value("${agent.batch-size:500}") int batchSize,
                                    @Value("${agent.checkpoint-file:}") String checkpointFile,
                                    @Value("${agent.startup-lookback:PT0S}") Duration startupLookback) {
        this(reader(jdbcTemplate), customerId, resourceName, batchSize,
                checkpointFile == null || checkpointFile.isBlank()
                        ? null : new CheckpointStore(Path.of(checkpointFile)),
                startupLookback);
    }

    /**
     * A real database, no checkpoint file: the cursor lives only in memory
     * and a restart starts at now. This is what the agent did before
     * checkpointing existed, and it is what the integration tests want,
     * since each builds a collector against a container it owns.
     */
    MySqlGeneralLogCollector(JdbcTemplate jdbcTemplate, String customerId, String resourceName, int batchSize) {
        this(reader(jdbcTemplate), customerId, resourceName, batchSize, null, Duration.ZERO);
    }

    MySqlGeneralLogCollector(RowReader rowReader, String customerId, String resourceName, int batchSize) {
        this(rowReader, customerId, resourceName, batchSize, null, Duration.ZERO);
    }

    /** Explicit start position, so tests do not depend on the wall clock. */
    MySqlGeneralLogCollector(RowReader rowReader, String customerId, String resourceName, int batchSize,
                             Instant startAt) {
        this(rowReader, customerId, resourceName, batchSize, new Cursor(startAt, Long.MIN_VALUE, Set.of()), null);
    }

    /**
     * The real constructor. The starting cursor is the saved one when there
     * is a trustworthy one, otherwise {@code now - lookback}.
     *
     * <p>Zero lookback means "start at now": on a first run with no
     * checkpoint the existing log is history nobody asked for, and reading
     * it would flood the platform. A first deploy that does want some of it
     * can set {@code agent.startup-lookback}.
     */
    MySqlGeneralLogCollector(RowReader rowReader, String customerId, String resourceName, int batchSize,
                             CheckpointStore checkpointStore, Duration startupLookback) {
        this(rowReader, customerId, resourceName, batchSize,
                startCursor(checkpointStore, startupLookback), checkpointStore);
        if (checkpointStore != null) {
            log.info("Collector starting at {}/{} (checkpoint file {})",
                    checkpoint.since(), checkpoint.sinceThreadId(), checkpointStore.file());
        }
    }

    /**
     * The saved position when there is one to trust, otherwise
     * {@code now - lookback}. The lookback applies only on a first run: once
     * a checkpoint exists it is the whole answer, and re-applying a lookback
     * on top of it would re-read rows on every restart.
     */
    private static Cursor startCursor(CheckpointStore store, Duration startupLookback) {
        if (store != null) {
            Optional<Cursor> saved = store.load();
            if (saved.isPresent()) {
                return saved.get();
            }
        }
        Duration lookback = startupLookback == null ? Duration.ZERO : startupLookback;
        return new Cursor(Instant.now().minus(lookback), Long.MIN_VALUE, Set.of());
    }

    private MySqlGeneralLogCollector(RowReader rowReader, String customerId, String resourceName, int batchSize,
                                     Cursor startCursor, CheckpointStore checkpointStore) {
        this.rowReader = rowReader;
        this.customerId = customerId;
        this.resourceName = resourceName;
        this.batchSize = batchSize;
        this.checkpointStore = checkpointStore;
        this.checkpoint = startCursor;
        this.pending = startCursor;
    }

    static RowReader reader(JdbcTemplate jdbcTemplate) {
        return (since, sinceThreadId, limit) -> jdbcTemplate.query(QUERY, (rs, rowNum) -> {
            byte[] argumentBytes = rs.getBytes("argument");
            String argument = argumentBytes == null ? "" : new String(argumentBytes, StandardCharsets.UTF_8);
            return new RawRow(rs.getTimestamp("event_time").toInstant(), rs.getString("user_host"),
                    rs.getLong("thread_id"), argument);
        }, Timestamp.from(since), Timestamp.from(since), sinceThreadId, limit);
    }

    @Override
    public List<AuditEvent> collect() {
        Cursor current = checkpoint;
        List<RawRow> rows = rowReader.read(current.since(), current.sinceThreadId(), batchSize);

        List<AuditEvent> events = new ArrayList<>();
        for (RawRow row : rows) {
            // rows at the boundary timestamp a previous batch already
            // returned; the query is >= so they come back every time
            if (current.seenAtBoundary().contains(key(row))) {
                continue;
            }
            AuditEvent event = toEvent(row.eventTime(), row.userHost(), row.threadId(), row.statement());
            if (event != null) {
                events.add(event);
            }
        }

        Cursor next = advance(current, rows);
        if (rows.size() == batchSize && next.equals(current)) {
            // Should be unreachable with keyset pagination: it would mean a
            // whole batch of rows sharing one (event_time, thread_id).
            log.warn("A full batch of {} rows did not advance the cursor past {}/{}; "
                    + "consider raising agent.batch-size",
                    batchSize, current.since(), current.sinceThreadId());
        }
        pending = next;
        return events;
    }

    @Override
    public void commit() {
        checkpoint = pending;
        if (checkpointStore != null) {
            checkpointStore.save(checkpoint);
        }
    }

    @Override
    public String sourceName() {
        return "mysql-general-log:" + resourceName;
    }

    /**
     * The cursor after this batch: the last row actually returned, never a
     * time read from elsewhere in the table.
     */
    static Cursor advance(Cursor current, List<RawRow> rows) {
        if (rows.isEmpty()) {
            return current;
        }
        RawRow lastRow = rows.get(rows.size() - 1);
        Instant last = lastRow.eventTime();
        long lastThread = lastRow.threadId();

        // only rows sharing the boundary's exact (time, thread) can come back
        // on the next poll, so only those need remembering
        Set<String> atBoundary = new HashSet<>();
        for (RawRow row : rows) {
            if (row.eventTime().equals(last) && row.threadId() == lastThread) {
                atBoundary.add(key(row));
            }
        }
        if (last.equals(current.since()) && lastThread == current.sinceThreadId()) {
            atBoundary.addAll(current.seenAtBoundary());
        }
        return new Cursor(last, lastThread, Set.copyOf(atBoundary));
    }

    /** Identifies a row within its timestamp. Same value as the event id. */
    private static String key(RawRow row) {
        return deterministicId(row.eventTime(), row.threadId(),
                row.statement() == null ? "" : row.statement().trim());
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
