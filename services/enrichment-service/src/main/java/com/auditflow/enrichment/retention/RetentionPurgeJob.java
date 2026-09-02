package com.auditflow.enrichment.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Deletes Aurora event metadata past the retention window, in batches.
 * Batching keeps each statement short (no long-held locks on a table the
 * enrichment consumer is inserting into) and makes progress visible in
 * the log if a first run has months of backlog to clear.
 *
 * <p>Deliberately Aurora-only: the S3 evidence is immutable by design and
 * Kafka's retention is a topic setting declared by the producers.
 */
@Component
public class RetentionPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeJob.class);

    // ctid-free, portable form: pick a batch of the oldest ids, delete them.
    static final String DELETE_BATCH_SQL = """
            DELETE FROM audit_events
            WHERE event_id IN (
                SELECT event_id FROM audit_events
                WHERE occurred_at < ?
                ORDER BY occurred_at
                LIMIT ?
            )
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RetentionProperties props;
    private final Clock clock;

    @Autowired
    public RetentionPurgeJob(JdbcTemplate jdbcTemplate, RetentionProperties props) {
        this(jdbcTemplate, props, Clock.systemUTC());
    }

    RetentionPurgeJob(JdbcTemplate jdbcTemplate, RetentionProperties props, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(cron = "${audit.retention.cron:0 15 3 * * *}")
    public void scheduledPurge() {
        if (!props.enabled()) {
            return;
        }
        purge();
    }

    /** @return total rows deleted */
    public long purge() {
        Instant cutoff = clock.instant().minus(Duration.ofDays(props.days()));
        long total = 0;
        int deleted;
        do {
            deleted = jdbcTemplate.update(DELETE_BATCH_SQL, Timestamp.from(cutoff), props.batchSize());
            total += deleted;
        } while (deleted == props.batchSize());
        if (total > 0) {
            log.info("Retention purge removed {} audit_events rows older than {} ({} days)", total, cutoff, props.days());
        } else {
            log.debug("Retention purge found nothing older than {}", cutoff);
        }
        return total;
    }
}
