package com.auditflow.enrichment.adapters;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.model.AuditEvent;
import com.auditflow.common.model.ComplianceControl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies AuroraWriterAdapter against a real Postgres instance, not a
 * mocked JdbcTemplate, per the "no mocking internal components" principle.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class AuroraWriterAdapterIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("auditflow")
            .withUsername("auditflow")
            .withPassword("auditflow");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private AuroraWriterAdapter auroraWriterAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void persistsEventMetadataToPostgres() {
        AuditEvent event = AuditEvent.builder()
                .eventId("evt-pg-1")
                .customerId("cust-1")
                .userId("user-1")
                .type(EventType.DATA_EXPORT)
                .resource("customers_table")
                .action("EXPORT")
                .riskLevel(RiskLevel.HIGH)
                .anomalous(true)
                .controls(List.of(
                        ComplianceControl.builder().framework("SOC2").controlId("AU-2").build(),
                        ComplianceControl.builder().framework("GDPR").controlId("Art-30").build()))
                .timestamp(Instant.now())
                .build();

        auroraWriterAdapter.write(event);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_events WHERE event_id = ?", Integer.class, "evt-pg-1");
        assertThat(count).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT controls FROM audit_events WHERE event_id = ?", String.class, "evt-pg-1"))
                .isEqualTo("SOC2:AU-2,GDPR:Art-30");
    }
    @Test
    void sameEventIdUnderTwoTenantsIsTwoRowsAndARedeliveryIsOne() {
        // Event ids come from the source - the collector agent hashes
        // (time, thread, statement), so two customers running the same query
        // at the same moment genuinely collide. Keyed on event_id alone the
        // second customer's event was silently swallowed by ON CONFLICT.
        AuditEvent forCustomer1 = collision("cust-1");
        AuditEvent forCustomer2 = collision("cust-2");

        auroraWriterAdapter.write(forCustomer1);
        auroraWriterAdapter.write(forCustomer2);
        // Kafka is at-least-once: the same event arriving twice must still
        // be one row, which is what the conflict clause is for
        auroraWriterAdapter.write(forCustomer1);

        assertThat(rowsWithEventId("evt-shared")).isEqualTo(2);
        assertThat(rowsFor("cust-1", "evt-shared")).isEqualTo(1);
        assertThat(rowsFor("cust-2", "evt-shared")).isEqualTo(1);
    }

    private static AuditEvent collision(String customerId) {
        return AuditEvent.builder()
                .eventId("evt-shared")
                .customerId(customerId)
                .type(EventType.DATABASE_QUERY)
                .resource("customers_table")
                .action("SELECT")
                .timestamp(Instant.now())
                .build();
    }

    private Integer rowsWithEventId(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_events WHERE event_id = ?", Integer.class, eventId);
    }

    private Integer rowsFor(String customerId, String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_events WHERE customer_id = ? AND event_id = ?",
                Integer.class, customerId, eventId);
    }
}

