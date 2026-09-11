package com.auditflow.agent.collector;

import com.auditflow.agent.collector.MySqlGeneralLogCollector.Cursor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Persists the collector's read position so a restart resumes where the
 * last one stopped.
 *
 * <p>Without this the cursor is exact but lives only in memory, and a fresh
 * agent starts at {@code Instant.now()} - so every statement logged while
 * the agent was down is skipped silently. That is the gap an audit trail
 * can least afford: the window where nobody was watching is exactly the
 * window worth reading.
 *
 * <h2>Why the write is not just a write</h2>
 *
 * <p>The file is written to a sibling {@code .tmp} and moved into place.
 * A checkpoint truncated by a crash mid-write is worse than no checkpoint:
 * it would parse as a valid but wrong position and skip everything between.
 * The move is atomic where the filesystem supports it, so a reader sees
 * either the old file or the new one, never half of either.
 */
class CheckpointStore {

    private static final Logger log = LoggerFactory.getLogger(CheckpointStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;

    CheckpointStore(Path file) {
        this.file = file;
    }

    Path file() {
        return file;
    }

    /**
     * The saved position, or empty when there is none to trust.
     *
     * <p>Anything unreadable is treated as absent, with a warning, rather
     * than as a reason to refuse to start: the caller then falls back to its
     * configured lookback. The one thing that must never happen is a
     * <em>partial</em> read being treated as complete - a cursor missing its
     * thread id would silently skip every row before whatever the default
     * happened to be - so every field is required.
     */
    Optional<Cursor> load() {
        if (!Files.exists(file)) {
            log.info("No checkpoint at {}; starting from the configured lookback", file);
            return Optional.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readAllBytes(file));
            JsonNode since = root.get("since");
            JsonNode sinceThreadId = root.get("sinceThreadId");
            JsonNode seen = root.get("seenAtBoundary");
            if (since == null || sinceThreadId == null || !sinceThreadId.isIntegralNumber()
                    || seen == null || !seen.isArray()) {
                log.warn("Checkpoint {} is missing fields; ignoring it and starting from the "
                        + "configured lookback instead of trusting a partial position", file);
                return Optional.empty();
            }
            Set<String> seenAtBoundary = new LinkedHashSet<>();
            for (JsonNode id : seen) {
                seenAtBoundary.add(id.asText());
            }
            return Optional.of(new Cursor(Instant.parse(since.asText()),
                    sinceThreadId.asLong(), Set.copyOf(seenAtBoundary)));
        } catch (Exception e) {
            log.warn("Could not read checkpoint {}: {}. Starting from the configured lookback.",
                    file, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Writes the position. A failure here is logged, not thrown: the events
     * this cursor covers have already been delivered, and taking the agent
     * down would not un-deliver them. The cost of a lost save is re-reading
     * some rows after a restart, which dedupes downstream on the event id.
     */
    void save(Cursor cursor) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("since", cursor.since().toString());
        root.put("sinceThreadId", cursor.sinceThreadId());
        ArrayNode seen = root.putArray("seenAtBoundary");
        cursor.seenAtBoundary().forEach(seen::add);

        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.write(tmp, MAPPER.writeValueAsBytes(root));
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // Some mounted volumes cannot do it. Still better than
                // writing the real file in place.
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Could not save checkpoint to {}: {}. Delivery already happened, so this "
                    + "only means re-reading some rows after a restart.", file, e.toString());
        }
    }
}
