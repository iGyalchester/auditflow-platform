package com.auditflow.gateway.controllers;

import com.auditflow.gateway.alerts.AlertHistoryEntry;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the alerts read path against a real Postgres instance,
 * including the rule-name join and customer scoping.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AlertControllerIntegrationTest {

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
                CREATE TABLE IF NOT EXISTS alert_rules (
                    rule_id       VARCHAR(64) PRIMARY KEY,
                    customer_id   VARCHAR(64) NOT NULL,
                    name          VARCHAR(255) NOT NULL,
                    event_type    VARCHAR(32),
                    risk_threshold VARCHAR(16),
                    condition_expression TEXT,
                    enabled       BOOLEAN NOT NULL DEFAULT true,
                    notification_channels VARCHAR(255)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS alert_history (
                    alert_id      VARCHAR(64) PRIMARY KEY,
                    rule_id       VARCHAR(64) NOT NULL REFERENCES alert_rules(rule_id),
                    event_id      VARCHAR(64) NOT NULL,
                    customer_id   VARCHAR(64) NOT NULL,
                    triggered_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                    notified_channels VARCHAR(255)
                )
                """);
        jdbcTemplate.update("DELETE FROM alert_history");
        jdbcTemplate.update("DELETE FROM alert_rules");
        jdbcTemplate.update("""
                INSERT INTO alert_rules (rule_id, customer_id, name, enabled)
                VALUES ('rule-1', 'cust-1', 'High-risk exports', true)
                """);
        jdbcTemplate.update("""
                INSERT INTO alert_history (alert_id, rule_id, event_id, customer_id, notified_channels)
                VALUES ('alert-1', 'rule-1', 'evt-1', 'cust-1', 'slack')
                """);
        jdbcTemplate.update("""
                INSERT INTO alert_history (alert_id, rule_id, event_id, customer_id, notified_channels)
                VALUES ('alert-2', 'rule-1', 'evt-2', 'cust-2', 'email')
                """);
    }

    @Test
    void returnsAlertsForCustomerWithRuleName() {
        ResponseEntity<AlertHistoryEntry[]> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/api/v1/alerts?customerId=cust-1", AlertHistoryEntry[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody()[0].alertId()).isEqualTo("alert-1");
        assertThat(response.getBody()[0].ruleName()).isEqualTo("High-risk exports");
        assertThat(response.getBody()[0].notifiedChannels()).isEqualTo("slack");
    }

    @Test
    void doesNotLeakOtherCustomersAlerts() {
        ResponseEntity<AlertHistoryEntry[]> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/api/v1/alerts?customerId=cust-2", AlertHistoryEntry[].class);

        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody()[0].alertId()).isEqualTo("alert-2");
    }
}
