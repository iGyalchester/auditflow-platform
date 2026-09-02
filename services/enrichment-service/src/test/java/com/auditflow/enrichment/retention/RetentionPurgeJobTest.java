package com.auditflow.enrichment.retention;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetentionPurgeJobTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Instant now = Instant.parse("2026-09-02T03:15:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    @Test
    void deletesInBatchesUntilABatchComesBackShort() {
        RetentionPurgeJob job = new RetentionPurgeJob(jdbc, new RetentionProperties(true, 400, 1000, ""), clock);
        Timestamp cutoff = Timestamp.from(now.minusSeconds(400L * 86_400));
        when(jdbc.update(eq(RetentionPurgeJob.DELETE_BATCH_SQL), eq(cutoff), eq(1000)))
                .thenReturn(1000, 1000, 250);

        assertThat(job.purge()).isEqualTo(2250);
        verify(jdbc, times(3)).update(eq(RetentionPurgeJob.DELETE_BATCH_SQL), eq(cutoff), eq(1000));
    }

    @Test
    void nothingToDeleteIsOneStatement() {
        RetentionPurgeJob job = new RetentionPurgeJob(jdbc, new RetentionProperties(true, 400, 1000, ""), clock);
        Timestamp cutoff = Timestamp.from(now.minusSeconds(400L * 86_400));
        when(jdbc.update(eq(RetentionPurgeJob.DELETE_BATCH_SQL), eq(cutoff), anyInt())).thenReturn(0);

        assertThat(job.purge()).isZero();
        verify(jdbc, times(1)).update(eq(RetentionPurgeJob.DELETE_BATCH_SQL), eq(cutoff), eq(1000));
    }

    @Test
    void disabledScheduleRunsNothing() {
        RetentionPurgeJob job = new RetentionPurgeJob(jdbc, new RetentionProperties(false, 400, 1000, ""), clock);

        job.scheduledPurge();

        verify(jdbc, never()).update(eq(RetentionPurgeJob.DELETE_BATCH_SQL), any(), anyInt());
    }

    @Test
    void retentionShorterThanADayIsRejectedAtStartup() {
        assertThatThrownBy(() -> new RetentionProperties(true, 0, 1000, ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("audit.retention.days");
    }
}
