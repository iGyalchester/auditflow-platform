package com.auditflow.agent.collector;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.model.AuditEvent;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MySqlGeneralLogCollectorTest {

    private final MySqlGeneralLogCollector collector = new MySqlGeneralLogCollector(
            (MySqlGeneralLogCollector.RowReader) (since, thread, limit) -> List.of(),
            "resistance", "resistance-mysql", 500);

    private final Instant now = Instant.parse("2026-08-31T12:00:00Z");

    @Test
    void mapsALogRowToARedactedEvent() {
        AuditEvent event = collector.toEvent(now, "springstudent[springstudent] @ localhost [127.0.0.1]",
                42L, "SELECT * FROM job_application WHERE owner_account_id = 7");

        assertThat(event.getType()).isEqualTo(EventType.DATABASE_QUERY);
        assertThat(event.getCustomerId()).isEqualTo("resistance");
        assertThat(event.getUserId()).isEqualTo("springstudent");
        assertThat(event.getResource()).isEqualTo("resistance-mysql");
        assertThat(event.getAction()).isEqualTo("SELECT");
        assertThat(event.getQuery()).isEqualTo("SELECT * FROM job_application WHERE owner_account_id = ?");
        assertThat(event.getRawLog()).isNull();
        assertThat(event.getEventId()).startsWith("mysql-");
    }

    @Test
    void eventIdIsDeterministicForTheSameRow() {
        String statement = "SELECT 1";
        AuditEvent first = collector.toEvent(now, "u[u] @ h []", 1L, statement);
        AuditEvent second = collector.toEvent(now, "u[u] @ h []", 1L, statement);
        AuditEvent different = collector.toEvent(now, "u[u] @ h []", 2L, statement);

        assertThat(first.getEventId()).isEqualTo(second.getEventId());
        assertThat(first.getEventId()).isNotEqualTo(different.getEventId());
    }

    @Test
    void filtersSelfReferentialAndSystemNoise() {
        assertThat(collector.toEvent(now, "u[u] @ h []", 1L,
                "SELECT MAX(event_time) FROM mysql.general_log")).isNull();
        assertThat(collector.toEvent(now, "u[u] @ h []", 1L,
                "SELECT * FROM information_schema.tables")).isNull();
        assertThat(collector.toEvent(now, "u[u] @ h []", 1L, "SET autocommit=1")).isNull();
        assertThat(collector.toEvent(now, "u[u] @ h []", 1L, "SHOW VARIABLES")).isNull();
        assertThat(collector.toEvent(now, "u[u] @ h []", 1L, "select @@session.tx_isolation")).isNull();
        assertThat(collector.toEvent(now, "u[u] @ h []", 1L, "")).isNull();
    }

    @Test
    void unparseableUserHostFallsBackSafely() {
        assertThat(collector.toEvent(now, null, 1L, "SELECT 1").getUserId()).isEqualTo("unknown");
        assertThat(collector.toEvent(now, "plainuser", 1L, "SELECT 1").getUserId()).isEqualTo("plainuser");
    }
    /** An in-memory general_log: the query's semantics, none of the database. */
    private static final class FakeLog implements MySqlGeneralLogCollector.RowReader {
        private final List<MySqlGeneralLogCollector.RawRow> rows = new ArrayList<>();

        void add(Instant at, long threadId, String statement) {
            rows.add(new MySqlGeneralLogCollector.RawRow(at, "u[u] @ h []", threadId, statement));
        }

        @Override
        public List<MySqlGeneralLogCollector.RawRow> read(Instant since, long sinceThreadId, int limit) {
            // the keyset predicate, exactly as the SQL expresses it
            return rows.stream()
                    .filter(r -> r.eventTime().isAfter(since)
                            || (r.eventTime().equals(since) && r.threadId() >= sinceThreadId))
                    .sorted(Comparator.comparing(MySqlGeneralLogCollector.RawRow::eventTime)
                            .thenComparing(MySqlGeneralLogCollector.RawRow::threadId))
                    .limit(limit)
                    .toList();
        }
    }

    private static final Instant EPOCH = Instant.parse("2026-08-31T12:00:00Z");

    private static MySqlGeneralLogCollector collectorOver(FakeLog log, int batchSize) {
        // start exactly at the first row, not at wall-clock now
        return new MySqlGeneralLogCollector(log, "resistance", "resistance-mysql", batchSize, EPOCH);
    }

    /**
     * Polls a fixed number of times, committing each batch. Deliberately not
     * "until a batch is empty": a batch of nothing but noise, or of rows the
     * previous batch already returned, yields no events while still moving
     * the cursor - stopping there would hide exactly the progress these
     * tests are about.
     */
    private static List<String> drain(MySqlGeneralLogCollector collector, int polls) {
        List<String> ids = new ArrayList<>();
        for (int poll = 0; poll < polls; poll++) {
            List<AuditEvent> batch = collector.collect();
            collector.commit();
            batch.forEach(e -> ids.add(e.getEventId()));
        }
        return ids;
    }

    @Test
    void drainsABacklogLargerThanOneBatchWithoutGaps() {
        // The bug this replaces: the checkpoint came from SELECT MAX over
        // the whole table, so with 1,200 rows and a batch of 500 the cursor
        // jumped straight to the newest row and 700 were never read.
        FakeLog log = new FakeLog();
        Instant start = Instant.parse("2026-08-31T12:00:00Z");
        int expected = 0;
        for (int i = 0; i < 1_200; i++) {
            if (i % 7 == 0) {
                log.add(start.plusMillis(i), i, "SET NAMES utf8");   // noise, filtered
            } else {
                log.add(start.plusMillis(i), i, "SELECT " + i + " FROM job_application");
                expected++;
            }
        }

        List<String> collected = drain(collectorOver(log, 500), 20);

        assertThat(collected).hasSize(expected);
        assertThat(collected).doesNotHaveDuplicates();
    }

    @Test
    void rowsSharingATimestampAreNeitherLostNorRepeated() {
        // A busy server writes several rows inside one microsecond. ">" would
        // skip the rest of them; ">=" alone would re-read them forever.
        FakeLog log = new FakeLog();
        Instant same = Instant.parse("2026-08-31T12:00:00Z");
        for (int thread = 1; thread <= 5; thread++) {
            log.add(same, thread, "SELECT " + thread + " FROM job_application");
        }
        log.add(same.plusMillis(1), 6, "SELECT 6 FROM job_application");

        List<String> collected = drain(collectorOver(log, 2), 20);

        assertThat(collected).hasSize(6);
        assertThat(collected).doesNotHaveDuplicates();
    }

    @Test
    void aBatchOfNothingButNoiseStillAdvances() {
        // No events to publish, but the cursor must still move or the same
        // noise is re-read on every poll forever.
        FakeLog log = new FakeLog();
        Instant start = Instant.parse("2026-08-31T12:00:00Z");
        for (int i = 0; i < 10; i++) {
            log.add(start.plusMillis(i), i, "SHOW STATUS");
        }
        log.add(start.plusMillis(100), 99, "SELECT 1 FROM job_application");

        List<String> collected = drain(collectorOver(log, 3), 20);

        assertThat(collected).hasSize(1);
        assertThat(collected).doesNotHaveDuplicates();
    }

    @Test
    void withoutACommitTheSameRowsAreReadAgain() {
        // The at-least-once seam: a failed publish must not advance anything.
        FakeLog log = new FakeLog();
        Instant start = Instant.parse("2026-08-31T12:00:00Z");
        log.add(start, 1, "SELECT 1 FROM job_application");
        log.add(start.plusMillis(1), 2, "SELECT 2 FROM job_application");
        MySqlGeneralLogCollector collector = collectorOver(log, 10);

        List<AuditEvent> first = collector.collect();
        List<AuditEvent> second = collector.collect();   // no commit in between

        assertThat(first).hasSize(2);
        assertThat(second).extracting(AuditEvent::getEventId)
                .containsExactlyElementsOf(first.stream().map(AuditEvent::getEventId).toList());
    }

    @Test
    void advanceNeverMovesPastTheLastRowReturned() {
        Instant t0 = Instant.parse("2026-08-31T12:00:00Z");
        MySqlGeneralLogCollector.Cursor start =
                new MySqlGeneralLogCollector.Cursor(t0, Long.MIN_VALUE, java.util.Set.of());

        assertThat(MySqlGeneralLogCollector.advance(start, List.of()).since())
                .as("an empty batch leaves the cursor alone").isEqualTo(t0);

        var rows = List.of(
                new MySqlGeneralLogCollector.RawRow(t0, "u[u] @ h []", 1L, "SELECT 1"),
                new MySqlGeneralLogCollector.RawRow(t0.plusMillis(5), "u[u] @ h []", 2L, "SELECT 2"));
        var next = MySqlGeneralLogCollector.advance(start, rows);
        assertThat(next.since())
                .as("the last row returned, not the newest row in the table")
                .isEqualTo(t0.plusMillis(5));
        assertThat(next.sinceThreadId()).isEqualTo(2L);
        assertThat(next.seenAtBoundary())
                .as("only rows sharing the boundary's exact time and thread")
                .hasSize(1);
    }

    @TempDir
    Path checkpointDir;

    /**
     * These tests exercise the *start* position, so unlike the cursor tests
     * they cannot pin the clock with the explicit-start constructor - the
     * whole point is what the collector chooses when nothing tells it. So
     * the rows sit a few minutes in the real past and the lookback is wide
     * enough to reach them.
     */
    private static final Duration REACHES_THE_ROWS = Duration.ofHours(1);

    private static Instant recently() {
        return Instant.now().minus(Duration.ofMinutes(5));
    }

    /**
     * The gap this exists to close. The cursor was exact but in memory only,
     * so a restarted agent began at Instant.now() and every statement logged
     * while it was down was skipped - silently, and precisely for the window
     * where nobody was watching.
     */
    @Test
    void aNewInstanceResumesFromTheSavedCursorInsteadOfSkippingTheGap() {
        Instant base = recently();
        FakeLog log = new FakeLog();
        log.add(base, 1, "SELECT 1 FROM job_application");
        log.add(base.plusMillis(10), 2, "SELECT 2 FROM job_application");

        CheckpointStore store = new CheckpointStore(checkpointDir.resolve("agent.checkpoint"));
        MySqlGeneralLogCollector first = new MySqlGeneralLogCollector(
                log, "resistance", "resistance-mysql", 500, store, REACHES_THE_ROWS);
        assertThat(first.collect()).hasSize(2);
        first.commit();

        // logged while the agent is "down"
        log.add(base.plusMillis(20), 3, "SELECT 3 FROM job_application");

        // a fresh lookback would re-read everything; the checkpoint must win
        MySqlGeneralLogCollector restarted = new MySqlGeneralLogCollector(
                log, "resistance", "resistance-mysql", 500, store, REACHES_THE_ROWS);
        List<AuditEvent> afterRestart = restarted.collect();

        assertThat(afterRestart)
                .as("the row logged during the outage, and only that row")
                .hasSize(1);
        assertThat(afterRestart.get(0).getQuery()).isEqualTo("SELECT ? FROM job_application");
    }

    /**
     * The boundary ids have to survive the restart too, or the row sitting
     * exactly on the cursor is delivered twice.
     */
    @Test
    void aRowOnTheBoundaryIsNotRedeliveredAfterARestart() {
        FakeLog log = new FakeLog();
        log.add(recently(), 1, "SELECT 1 FROM job_application");

        CheckpointStore store = new CheckpointStore(checkpointDir.resolve("agent.checkpoint"));
        MySqlGeneralLogCollector first = new MySqlGeneralLogCollector(
                log, "resistance", "resistance-mysql", 500, store, REACHES_THE_ROWS);
        assertThat(first.collect()).hasSize(1);
        first.commit();

        MySqlGeneralLogCollector restarted = new MySqlGeneralLogCollector(
                log, "resistance", "resistance-mysql", 500, store, REACHES_THE_ROWS);

        assertThat(restarted.collect())
                .as("the boundary row was already delivered")
                .isEmpty();
    }

    /**
     * An uncommitted batch must not be saved. The runner only commits after
     * ingestion accepted the events, so a save before that would lose
     * whatever the failed publish covered.
     */
    @Test
    void anUncommittedBatchLeavesTheSavedCursorAlone() {
        FakeLog log = new FakeLog();
        log.add(recently(), 1, "SELECT 1 FROM job_application");

        CheckpointStore store = new CheckpointStore(checkpointDir.resolve("agent.checkpoint"));
        MySqlGeneralLogCollector collector = new MySqlGeneralLogCollector(
                log, "resistance", "resistance-mysql", 500, store, REACHES_THE_ROWS);
        collector.collect(); // no commit: pretend the publish failed

        assertThat(store.load()).isEmpty();

        MySqlGeneralLogCollector restarted = new MySqlGeneralLogCollector(
                log, "resistance", "resistance-mysql", 500, store, REACHES_THE_ROWS);
        assertThat(restarted.collect())
                .as("re-read after a failed publish, not lost")
                .hasSize(1);
    }

    /**
     * With no checkpoint the lookback decides the start. Zero means now, so
     * a first run does not replay the whole existing log into ingestion.
     */
    @Test
    void theLookbackAppliesOnlyWhenThereIsNoCheckpoint() {
        FakeLog log = new FakeLog();
        log.add(recently(), 1, "SELECT 1 FROM job_application");

        CheckpointStore store = new CheckpointStore(checkpointDir.resolve("agent.checkpoint"));

        MySqlGeneralLogCollector withoutLookback = new MySqlGeneralLogCollector(
                log, "resistance", "resistance-mysql", 500, store, Duration.ZERO);
        assertThat(withoutLookback.collect())
                .as("minutes old, and the agent started at now")
                .isEmpty();

        MySqlGeneralLogCollector withLookback = new MySqlGeneralLogCollector(
                log, "resistance", "resistance-mysql", 500, store, REACHES_THE_ROWS);
        assertThat(withLookback.collect()).hasSize(1);
    }

    @Test
    void aTimestampWithMoreRowsThanOneBatchStillDrains() {
        // The failure mode that keyset pagination exists for: with a plain
        // ">= time" cursor the query kept returning the same first two rows
        // and the collector never got past them.
        FakeLog log = new FakeLog();
        for (int thread = 1; thread <= 7; thread++) {
            log.add(EPOCH, thread, "SELECT " + thread + " FROM job_application");
        }

        List<String> collected = drain(collectorOver(log, 2), 20);

        assertThat(collected).hasSize(7);
        assertThat(collected).doesNotHaveDuplicates();
    }
}
