package com.auditflow.gateway.controllers;

import com.auditflow.gateway.audit.AuditLogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the audit-logs read path against a real Postgres instance via
 * Testcontainers - no mocked JdbcTemplate - mirroring the enrichment-service
 * write-side integration tests. Verifies customer scoping, ordering, and that
 * the endpoint refuses to list without a customer.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditLogControllerIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("auditflow")
            .withUsername("auditflow")
            .withPassword("auditflow");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS audit_events (
                    event_id      VARCHAR(64) PRIMARY KEY,
                    customer_id   VARCHAR(64) NOT NULL,
                    user_id       VARCHAR(64),
                    session_id    VARCHAR(64),
                    occurred_at   TIMESTAMPTZ NOT NULL,
                    event_type    VARCHAR(32) NOT NULL,
                    resource      VARCHAR(512),
                    action        VARCHAR(128),
                    risk_level    VARCHAR(16),
                    anomalous     BOOLEAN NOT NULL DEFAULT false
                )
                """);
        jdbcTemplate.update("DELETE FROM audit_events");

        Instant base = Instant.parse("2026-08-05T10:00:00Z");
        insertEvent("evt-old", "cust-1", base, "DATABASE_QUERY", "LOW", false);
        insertEvent("evt-new", "cust-1", base.plus(1, ChronoUnit.HOURS), "DATA_EXPORT", "HIGH", true);
        insertEvent("evt-other", "cust-2", base, "API_CALL", "MEDIUM", false);
    }

    @Test
    void returnsEventsForCustomerNewestFirst() {
        ResponseEntity<AuditLogEntry[]> response = restTemplate.getForEntity(
                url("/api/v1/audit-logs?customerId=cust-1"), AuditLogEntry[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .extracting(AuditLogEntry::eventId)
                .containsExactly("evt-new", "evt-old");
        assertThat(response.getBody()[0].riskLevel()).isEqualTo("HIGH");
        assertThat(response.getBody()[0].anomalous()).isTrue();
    }

    @Test
    void doesNotLeakOtherCustomersEvents() {
        ResponseEntity<AuditLogEntry[]> response = restTemplate.getForEntity(
                url("/api/v1/audit-logs?customerId=cust-2"), AuditLogEntry[].class);

        assertThat(response.getBody())
                .extracting(AuditLogEntry::eventId)
                .containsExactly("evt-other");
    }

    @Test
    void limitCapsNumberOfRows() {
        ResponseEntity<AuditLogEntry[]> response = restTemplate.getForEntity(
                url("/api/v1/audit-logs?customerId=cust-1&limit=1"), AuditLogEntry[].class);

        assertThat(response.getBody())
                .extracting(AuditLogEntry::eventId)
                .containsExactly("evt-new");
    }

    @Test
    void returnsEmptyForUnknownCustomer() {
        ResponseEntity<AuditLogEntry[]> response = restTemplate.getForEntity(
                url("/api/v1/audit-logs?customerId=nobody"), AuditLogEntry[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void requiresCustomerId() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/api/v1/audit-logs"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private void insertEvent(String eventId, String customerId, Instant occurredAt,
                             String eventType, String riskLevel, boolean anomalous) {
        jdbcTemplate.update("""
                INSERT INTO audit_events
                    (event_id, customer_id, user_id, session_id, occurred_at, event_type, resource, action, risk_level, anomalous)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                eventId, customerId, "user-1", "sess-1", Timestamp.from(occurredAt),
                eventType, "customers_table", "SELECT", riskLevel, anomalous);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
