package com.auditflow.agent.collector;

import com.auditflow.agent.collector.MySqlGeneralLogCollector.Cursor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CheckpointStoreTest {

    private static final Instant WHEN = Instant.parse("2026-01-02T03:04:05.123456Z");

    @TempDir
    Path dir;

    private CheckpointStore store() {
        return new CheckpointStore(dir.resolve("agent.checkpoint"));
    }

    @Test
    void aSavedCursorComesBackExactly() {
        Cursor saved = new Cursor(WHEN, 4242L, Set.of("id-a", "id-b"));
        store().save(saved);

        assertThat(store().load()).contains(saved);
    }

    @Test
    void microsecondPrecisionSurvivesTheRoundTrip() {
        // The cursor is a keyset on (event_time, thread_id) and general_log
        // timestamps are microsecond-precision. Truncate to seconds on the
        // way through and a restart re-reads or skips a second of rows.
        store().save(new Cursor(WHEN, 1L, Set.of()));

        assertThat(store().load().orElseThrow().since()).isEqualTo(WHEN);
    }

    @Test
    void noFileMeansNoCursorRatherThanAnError() {
        assertThat(store().load()).isEmpty();
    }

    @Test
    void aCorruptFileIsIgnoredRatherThanFatal() throws Exception {
        Path file = dir.resolve("agent.checkpoint");
        Files.writeString(file, "{not json");

        assertThat(new CheckpointStore(file).load()).isEmpty();
    }

    /**
     * The dangerous case. A cursor missing its thread id would parse if the
     * field defaulted, and the collector would silently skip every row
     * before whatever the default happened to be. Treat it as absent so the
     * caller falls back to its lookback, which at worst re-reads.
     */
    @Test
    void aPartialCursorIsTreatedAsAbsentNotDefaulted() throws Exception {
        Path file = dir.resolve("agent.checkpoint");
        Files.writeString(file, "{\"since\":\"2026-01-02T03:04:05Z\",\"seenAtBoundary\":[]}");

        assertThat(new CheckpointStore(file).load()).isEmpty();
    }

    @Test
    void aLeftoverTempFileIsNotMistakenForTheCheckpoint() throws Exception {
        Path file = dir.resolve("agent.checkpoint");
        Files.writeString(file.resolveSibling("agent.checkpoint.tmp"), "{\"since\":\"bogus\"}");

        CheckpointStore store = new CheckpointStore(file);
        assertThat(store.load()).isEmpty();

        store.save(new Cursor(WHEN, 7L, Set.of()));
        assertThat(store.load().orElseThrow().sinceThreadId()).isEqualTo(7L);
    }

    @Test
    void savingTwiceLeavesOnlyTheLatest() {
        CheckpointStore store = store();
        store.save(new Cursor(WHEN, 1L, Set.of("old")));
        store.save(new Cursor(WHEN.plusSeconds(60), 2L, Set.of("new")));

        Optional<Cursor> loaded = store.load();
        assertThat(loaded).isPresent();
        assertThat(loaded.get().sinceThreadId()).isEqualTo(2L);
        assertThat(loaded.get().seenAtBoundary()).containsExactly("new");
    }

    @Test
    void aSaveIntoAMissingDirectoryCreatesIt() {
        Path nested = dir.resolve("a/b/agent.checkpoint");
        CheckpointStore store = new CheckpointStore(nested);

        store.save(new Cursor(WHEN, 9L, Set.of()));

        assertThat(store.load().orElseThrow().sinceThreadId()).isEqualTo(9L);
    }

    /**
     * A save must never take the agent down: by the time commit() runs the
     * events are already delivered, and failing here would not un-deliver
     * them. The cost of a lost save is re-reading rows after a restart.
     */
    @Test
    void anUnwritableLocationIsLoggedNotThrown() {
        // a directory where the file should be - the write cannot succeed
        Path asDirectory = dir.resolve("occupied");
        assertThat(asDirectory.toFile().mkdirs()).isTrue();

        CheckpointStore store = new CheckpointStore(asDirectory);

        store.save(new Cursor(WHEN, 1L, Set.of()));
        assertThat(store.load()).isEmpty();
    }
}
