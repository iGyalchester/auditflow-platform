package com.auditflow.reporting.api;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generates reports over real Postgres data: seeded events carry controls in
 * event_controls, and the framework filter must include only events that are
 * evidence for the requested framework.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReportControllerIntegrationTest {

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

    private static final Instant BASE = Instant.parse("2026-08-01T00:00:00Z");

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
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS event_controls (
                    event_id      VARCHAR(64) NOT NULL,
                    customer_id   VARCHAR(64) NOT NULL,
                    control_id    VARCHAR(32) NOT NULL,
                    framework     VARCHAR(32) NOT NULL,
                    name          VARCHAR(255),
                    PRIMARY KEY (event_id, control_id, framework)
                )
                """);
        jdbcTemplate.update("DELETE FROM event_controls");
        jdbcTemplate.update("DELETE FROM audit_events");

        seedEvent("evt-soc2", "cust-1", "DATA_EXPORT");
        seedControl("evt-soc2", "cust-1", "AU-2", "SOC2", "Audit Events");
        seedControl("evt-soc2", "cust-1", "Art-30", "GDPR", "Records of Processing Activities");

        seedEvent("evt-gdpr-only", "cust-1", "AUTH_EVENT");
        seedControl("evt-gdpr-only", "cust-1", "Art-32", "GDPR", "Security of Processing");

        seedEvent("evt-other-cust", "cust-2", "DATA_EXPORT");
        seedControl("evt-other-cust", "cust-2", "AU-2", "SOC2", "Audit Events");
    }

    private void seedEvent(String eventId, String customerId, String type) {
        jdbcTemplate.update("""
                INSERT INTO audit_events
                    (event_id, customer_id, occurred_at, event_type, risk_level, anomalous)
                VALUES (?, ?, ?, ?, 'HIGH', false)
                """, eventId, customerId, Timestamp.from(BASE.plusSeconds(60)), type);
    }

    private void seedControl(String eventId, String customerId, String controlId, String framework, String name) {
        jdbcTemplate.update("""
                INSERT INTO event_controls (event_id, customer_id, control_id, framework, name)
                VALUES (?, ?, ?, ?, ?)
                """, eventId, customerId, controlId, framework, name);
    }

    private String reportUrl(String framework, String customerId) {
        return "http://localhost:" + port
                + "/api/v1/reports?customerId=" + customerId
                + "&framework=" + framework
                + "&from=2026-08-01T00:00:00Z&to=2026-08-02T00:00:00Z";
    }

    @Test
    void soc2ReportContainsOnlySoc2Evidence() {
        ResponseEntity<String> response = restTemplate.getForEntity(reportUrl("SOC2", "cust-1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("SOC 2 Evidence Report");
        assertThat(response.getBody()).contains("evt-soc2");
        assertThat(response.getBody()).doesNotContain("evt-gdpr-only");
        assertThat(response.getBody()).doesNotContain("evt-other-cust");
    }

    @Test
    void gdprReportIncludesGdprScopedEvents() {
        ResponseEntity<String> response = restTemplate.getForEntity(reportUrl("GDPR", "cust-1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("evt-soc2");
        assertThat(response.getBody()).contains("evt-gdpr-only");
    }

    @Test
    void unknownFrameworkIsRejected() {
        ResponseEntity<String> response = restTemplate.getForEntity(reportUrl("PCI", "cust-1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void malformedTimeWindowIsRejected() {
        String url = "http://localhost:" + port
                + "/api/v1/reports?customerId=cust-1&framework=SOC2&from=yesterday&to=today";

        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
