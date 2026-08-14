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
                .timestamp(Instant.now())
                .build();

        auroraWriterAdapter.write(event);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_events WHERE event_id = ?", Integer.class, "evt-pg-1");
        assertThat(count).isEqualTo(1);
    }

    @Test
    void persistsAttachedControlsToEventControlsTable() {
        AuditEvent event = AuditEvent.builder()
                .eventId("evt-pg-2")
                .customerId("cust-1")
                .type(EventType.DATA_EXPORT)
                .riskLevel(RiskLevel.HIGH)
                .timestamp(Instant.now())
                .controls(List.of(
                        ComplianceControl.builder()
                                .controlId("AU-2").framework("SOC2").name("Audit Events").build(),
                        ComplianceControl.builder()
                                .controlId("Art-30").framework("GDPR").name("Records of Processing Activities").build()))
                .build();

        auroraWriterAdapter.write(event);

        List<String> frameworks = jdbcTemplate.queryForList(
                "SELECT framework FROM event_controls WHERE event_id = ?", String.class, "evt-pg-2");
        assertThat(frameworks).containsExactlyInAnyOrder("SOC2", "GDPR");
    }
}
