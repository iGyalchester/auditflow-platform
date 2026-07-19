package com.auditflow.reporting.queries;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AthenaQueryBuilderTest {

    private final AthenaQueryBuilder builder = new AthenaQueryBuilder();

    @Test
    void includesCustomerScopeAndTimeWindow() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-31T23:59:59Z");

        String sql = builder.buildEventsQuery("auditflow_db", "audit_events", "cust-1", from, to);

        assertThat(sql)
                .contains("\"auditflow_db\".\"audit_events\"")
                .contains("customer_id = 'cust-1'")
                .contains(from.toString())
                .contains(to.toString());
    }
}
