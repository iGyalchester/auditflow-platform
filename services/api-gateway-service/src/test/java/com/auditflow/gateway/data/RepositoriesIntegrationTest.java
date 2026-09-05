package com.auditflow.gateway.data;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
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
    private AlertRuleRepository rules;
    @Autowired
    private JdbcTemplate jdbc;

    /** One container for the class: every test starts from empty tables (FK order matters). */
    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM alert_history");
        jdbc.update("DELETE FROM alert_rules");
        jdbc.update("DELETE FROM audit_events");
    }

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

    @Test
    void rulesAreScopedOnEveryOperation() {
        rules.upsert(com.auditflow.common.model.AlertRule.builder().ruleId("rule-a").customerId("acme").name("A")
                .eventType(com.auditflow.common.enums.EventType.AUTH_EVENT)
                .conditionExpression("action == 'LOGIN_FAILURE'").notificationChannels(java.util.List.of("slack", "email")).build());
        rules.upsert(com.auditflow.common.model.AlertRule.builder().ruleId("rule-o").customerId("other-co").name("O").build());

        assertThat(rules.findAll("acme")).extracting(com.auditflow.common.model.AlertRule::getRuleId).containsExactly("rule-a");
        assertThat(rules.find("acme", "rule-a")).isPresent();
        assertThat(rules.find("acme", "rule-a").get().getNotificationChannels()).containsExactly("slack", "email");
        assertThat(rules.find("acme", "rule-o")).isEmpty();
        assertThat(rules.delete("acme", "rule-o")).isFalse();
        assertThat(rules.find("other-co", "rule-o")).isPresent();

        // an upsert under another customer's id does not hijack the row
        rules.upsert(com.auditflow.common.model.AlertRule.builder().ruleId("rule-o").customerId("acme").name("hijack").build());
        assertThat(rules.find("other-co", "rule-o").get().getName()).isEqualTo("O");

        assertThat(rules.delete("acme", "rule-a")).isTrue();
        assertThat(rules.findAll("acme")).isEmpty();
    }

    @Test
    void reportEventsCarryDecodedControlsAndHonourTheWindow() {
        Instant now = Instant.now();
        event("r-in", "acme", "DATA_EXPORT", now.minus(Duration.ofDays(3)));
        event("r-out", "acme", "DATA_EXPORT", now.minus(Duration.ofDays(40)));

        var events = auditLogs.findForReport("acme", now.minus(Duration.ofDays(30)), now, 100);

        assertThat(events).extracting(com.auditflow.common.model.AuditEvent::getEventId).contains("r-in").doesNotContain("r-out");
        var inWindow = events.stream().filter(e -> e.getEventId().equals("r-in")).findFirst().orElseThrow();
        assertThat(inWindow.getControls()).extracting(com.auditflow.common.model.ComplianceControl::getControlId).containsExactly("AC-2");
        assertThat(inWindow.getType()).isEqualTo(com.auditflow.common.enums.EventType.DATA_EXPORT);
    }

    @Test
    void anAlertOutlivesTheRuleThatRaisedIt() {
        rules.upsert(com.auditflow.common.model.AlertRule.builder()
                .ruleId("doomed").customerId("acme").name("Doomed").build());
        jdbc.update("INSERT INTO alert_history (alert_id, rule_id, event_id, customer_id, notified_channels) "
                + "VALUES ('al-1', 'doomed', 'evt-1', 'acme', 'slack')");

        // used to fail on the foreign key, making the rule undeletable
        assertThat(rules.delete("acme", "doomed")).isTrue();

        var listed = alerts.find("acme", 10);
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).alertId()).isEqualTo("al-1");
        // the LEFT JOIN already handled this: no rule, no attribution, but
        // the alert is still on the record
        assertThat(listed.get(0).ruleId()).isNull();
        assertThat(listed.get(0).ruleName()).isNull();
    }

    @Test
    void theSchemaCanBeAppliedTwice() throws Exception {
        // Every service runs this script on boot, so it meets a database it
        // has already created. The migration statements this slice adds are
        // ALTERs, which is exactly where that stops being free.
        try (var connection = jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("auditflow-schema.sql"));
        }

        // and the table is still the shape the code expects
        jdbc.update("INSERT INTO alert_history (alert_id, rule_id, event_id, customer_id) "
                + "VALUES ('al-2', NULL, 'evt-2', 'acme')");
        assertThat(alerts.find("acme", 10)).hasSize(1);
    }
}