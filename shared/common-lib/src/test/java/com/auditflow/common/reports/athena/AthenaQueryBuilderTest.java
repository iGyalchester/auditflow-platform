package com.auditflow.common.reports.athena;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AthenaQueryBuilderTest {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-01-31T23:59:59Z");

    private final AthenaQueryBuilder builder = new AthenaQueryBuilder();

    @Test
    void includesCustomerScopeAndTimeWindow() {
        String sql = builder.buildEventsQuery("auditflow_db", "audit_events", "cust-1", FROM, TO);

        assertThat(sql)
                .contains("\"auditflow_db\".\"audit_events\"")
                .contains("customer_id = 'cust-1'")
                // Athena timestamp literals: 'yyyy-MM-dd HH:mm:ss', not ISO-8601
                .contains("timestamp '2026-01-01 00:00:00'")
                .contains("timestamp '2026-01-31 23:59:59'");
    }

    @Test
    void escapesQuotesInCustomerId() {
        String sql = builder.buildEventsQuery("db", "t", "cust-1' OR '1'='1", FROM, TO);

        // the payload survives only as an inert, escaped literal
        assertThat(sql).contains("customer_id = 'cust-1'' OR ''1''=''1'");
        assertThat(sql).doesNotContain("= 'cust-1' OR ");
    }

    @Test
    void rejectsNonIdentifierDatabaseAndTable() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                builder.buildEventsQuery("db\"; DROP TABLE x; --", "audit_events", "cust-1", FROM, TO));
        assertThatIllegalArgumentException().isThrownBy(() ->
                builder.buildEventsQuery("db", "audit events", "cust-1", FROM, TO));
    }
}
