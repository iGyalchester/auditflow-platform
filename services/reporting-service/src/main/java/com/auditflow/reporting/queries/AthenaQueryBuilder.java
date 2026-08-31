package com.auditflow.reporting.queries;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * Builds the Athena SQL used to pull evidence out of the S3-backed audit
 * event lake for a given customer and time window.
 *
 * <p>Athena's StartQueryExecution has no JDBC-style prepared statements on
 * this path, so injection is prevented at build time instead: database and
 * table names must be plain identifiers (they come from configuration, but
 * validating here keeps the guarantee local), the customer id - the one
 * value that can carry end-user input - is embedded as a properly escaped
 * string literal, and timestamps are rendered from {@link Instant}s, never
 * from caller-supplied text. Athena execution parameters are the eventual
 * upgrade once queries actually execute.
 */
@Component
public class AthenaQueryBuilder {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    // Athena (Trino/Presto) timestamp literals take 'yyyy-MM-dd HH:mm:ss',
    // not ISO-8601's 'T'/'Z' form that Instant.toString() produces.
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    public String buildEventsQuery(String database, String table, String customerId, Instant from, Instant to) {
        return """
                SELECT *
                FROM "%s"."%s"
                WHERE customer_id = %s
                  AND event_time BETWEEN timestamp '%s' AND timestamp '%s'
                ORDER BY event_time
                """.formatted(
                identifier(database),
                identifier(table),
                stringLiteral(customerId),
                TIMESTAMP.format(from),
                TIMESTAMP.format(to));
    }

    private static String identifier(String name) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("Not a valid Athena identifier: " + name);
        }
        return name;
    }

    private static String stringLiteral(String value) {
        if (value == null) {
            throw new IllegalArgumentException("customerId is required");
        }
        return "'" + value.replace("'", "''") + "'";
    }
}
