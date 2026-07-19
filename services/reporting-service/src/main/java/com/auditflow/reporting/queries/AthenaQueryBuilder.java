package com.auditflow.reporting.queries;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Builds the Athena SQL used to pull evidence out of the S3-backed audit
 * event lake for a given customer and time window.
 */
@Component
public class AthenaQueryBuilder {

    public String buildEventsQuery(String database, String table, String customerId, Instant from, Instant to) {
        return """
                SELECT *
                FROM "%s"."%s"
                WHERE customer_id = '%s'
                  AND event_time BETWEEN timestamp '%s' AND timestamp '%s'
                ORDER BY event_time
                """.formatted(database, table, customerId, from, to);
    }
}
