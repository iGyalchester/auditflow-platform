package com.auditflow.enrichment.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long queryable event metadata stays in Aurora.
 *
 * <p>The evidence itself lives in S3 under Object Lock for at least the
 * infra's {@code object_lock_retention_days} (365 by default) and is never
 * expired automatically - the bucket policy forbids lifecycle rules on
 * purpose. Aurora holds a queryable copy for reports; keeping it a little
 * longer than the evidence minimum means a report can always find the
 * metadata for evidence that is still locked.
 *
 * @param enabled   run the purge at all
 * @param days      rows older than this many days are deleted; must be >= 1
 * @param batchSize rows per DELETE statement, so a big backlog never holds
 *                  one long lock
 * @param cron      when the purge runs (server time)
 */
@ConfigurationProperties(prefix = "audit.retention")
public record RetentionProperties(boolean enabled, int days, int batchSize, String cron) {

    public RetentionProperties {
        if (days < 1) {
            throw new IllegalArgumentException("audit.retention.days must be >= 1, was " + days);
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("audit.retention.batch-size must be >= 1, was " + batchSize);
        }
    }
}
