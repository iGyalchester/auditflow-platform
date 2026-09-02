package com.auditflow.gateway.data;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenant scoping and filters against a real Postgres, with the shared
 * schema applied by this service on startup (no other service needed).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class RepositoriesIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("auditflow").withUsername("auditflow").withPassword("auditflow");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private AuditLogRepository auditLogs;
    @Autowired
    private AlertHistoryRepository alerts;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void auditLogsAreScopedFilteredAndNewestFirst() {
        Instant now = Instant.now();
        event("a-old", "acme", "AUTH_EVENT", now.minus(Duration.ofDays(2)));
        event("a-new", "acme", "AUTH_EVENT", now);
        event("a-file", "acme", "FILE_ACCESS", now.minus(Duration.ofHours(1)));
        event("b-1", "other-co", "AUTH_EVENT", now);

        assertThat(auditLogs.find("acme", null, null, null, 100)).extracting(AuditLogRepository.AuditLogRow::eventId)
                .containsExactly("a-new", "a-file", "a-old");
        assertThat(auditLogs.find("acme", "AUTH_EVENT", null, null, 100)).extracting(AuditLogRepository.AuditLogRow::eventId)
                .containsExactly("a-new", "a-old");
        assertThat(auditLogs.find("acme", null, now.minus(Duration.ofDays(1)), null, 100))
                .extracting(AuditLogRepository.AuditLogRow::eventId).containsExactly("a-new", "a-file");
        assertThat(auditLogs.find("acme", null, null, null, 1)).hasSize(1);
        assertThat(auditLogs.find("nobody", null, null, null, 100)).isEmpty();
        // other-co's row never appears in acme's results
        assertThat(auditLogs.find("acme", null, null, null, 100)).extracting(AuditLogRepository.AuditLogRow::eventId)
                .doesNotContain("b-1");
    }

    @Test
    void alertsJoinTheRuleNameAndAreScoped() {
        jdbc.update("INSERT INTO alert_rules (rule_id, customer_id, name) VALUES ('r-acme', 'acme', 'Failed login')");
        jdbc.update("INSERT INTO alert_rules (rule_id, customer_id, name) VALUES ('r-other', 'other-co', 'Other')");
        jdbc.update("INSERT INTO alert_history (alert_id, rule_id, event_id, customer_id, notified_channels) VALUES ('al-1', 'r-acme', 'a-new', 'acme', 'slack')");
        jdbc.update("INSERT INTO alert_history (alert_id, rule_id, event_id, customer_id, notified_channels) VALUES ('al-2', 'r-other', 'b-1', 'other-co', 'email')");

        var rows = alerts.find("acme", 100);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).ruleName()).isEqualTo("Failed login");
        assertThat(rows.get(0).notifiedChannels()).isEqualTo("slack");
        assertThat(alerts.find("other-co", 100)).extracting(AlertHistoryRepository.AlertRow::alertId).containsExactly("al-2");
    }

    private void event(String id, String customer, String type, Instant at) {
        jdbc.update("INSERT INTO audit_events (event_id, customer_id, occurred_at, event_type, controls) VALUES (?, ?, ?, ?, ?)",
                id, customer, Timestamp.from(at), type, "SOC2:AC-2");
    }
}
