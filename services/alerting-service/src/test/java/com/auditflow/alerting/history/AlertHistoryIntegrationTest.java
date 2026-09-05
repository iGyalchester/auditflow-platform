package com.auditflow.alerting.history;

import com.auditflow.alerting.rules.JdbcRuleRepository;
import com.auditflow.alerting.rules.RuleSeeder;
import com.auditflow.common.enums.EventType;
import com.auditflow.common.model.AlertRule;
import com.auditflow.common.model.AuditEvent;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Against a real Postgres: the shared schema creates the tables, file rules
 * are synced into alert_rules, and an alert_history row can reference them
 * (the foreign key holds).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "audit.alerting.rules-file=classpath:test-rules.json",
        "spring.kafka.bootstrap-servers=localhost:9"   // no broker needed; listener just retries
})
class AlertHistoryIntegrationTest {

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
    private JdbcRuleRepository rules;
    @Autowired
    private AlertHistoryWriter history;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RuleSeeder seeder;

    /**
     * Every test starts from the freshly seeded state. Two of them mutate
     * alert_rules on purpose - that is what they are about - and JUnit
     * promises no order, so without this they would interfere.
     */
    @BeforeEach
    void reseed() {
        jdbc.update("DELETE FROM alert_history");
        jdbc.update("DELETE FROM alert_rules");
        seeder.load();
        rules.refresh();
    }

    @Test
    void fileRulesAreSyncedAndHistoryRowsReferenceThem() {
        Integer ruleRows = jdbc.queryForObject("SELECT count(*) FROM alert_rules", Integer.class);
        assertThat(ruleRows).isEqualTo(3);
        Map<String, Object> pii = jdbc.queryForMap("SELECT * FROM alert_rules WHERE rule_id = 'pii-view'");
        assertThat(pii.get("notification_channels")).isEqualTo("slack,email");
        assertThat(pii.get("risk_threshold")).isEqualTo("HIGH");

        rules.refresh();
        AlertRule rule = rules.rulesFor("resistance").get(0);
        AuditEvent event = AuditEvent.builder().eventId("evt-h1").customerId("resistance")
                .type(EventType.AUTH_EVENT).timestamp(Instant.now()).build();
        String alertId = history.record(rule, event, List.of("slack"));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM alert_history WHERE alert_id = ?", alertId);
        assertThat(row.get("rule_id")).isEqualTo(rule.getRuleId());
        assertThat(row.get("customer_id")).isEqualTo("resistance");
        assertThat(row.get("notified_channels")).isEqualTo("slack");
    }

    @Test
    void deletingARuleKeepsItsAlertsOnTheRecord() {
        // Its own rule, so this test neither depends on nor disturbs the
        // seeded ones.
        jdbc.update("INSERT INTO alert_rules (rule_id, customer_id, name, enabled) "
                + "VALUES ('doomed', 'resistance', 'Doomed', true)");
        AlertRule doomed = AlertRule.builder().ruleId("doomed").customerId("resistance").name("Doomed").build();
        AuditEvent event = AuditEvent.builder().eventId("evt-del").customerId("resistance")
                .type(EventType.AUTH_EVENT).timestamp(Instant.now()).build();
        String alertId = history.record(doomed, event, List.of("slack"));

        // This is what used to fail on the foreign key: the rule could not be
        // deleted at all unless someone deleted its history first, which is
        // the one thing an audit platform must not make people do.
        jdbc.update("DELETE FROM alert_rules WHERE rule_id = 'doomed'");

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM alert_history WHERE alert_id = ?", alertId);
        assertThat(row.get("rule_id")).as("attribution is dropped").isNull();
        assertThat(row.get("event_id")).as("the alert itself is still evidence").isEqualTo("evt-del");
        assertThat(row.get("notified_channels")).isEqualTo("slack");
    }

    @Test
    void anAlertForAnAlreadyDeletedRuleIsRecordedWithoutAttribution() {
        // The race: alerting holds its rules in memory for up to one refresh
        // interval, so it can still match a rule the API has just deleted.
        AlertRule stale = AlertRule.builder().ruleId("never-existed").customerId("resistance").name("Stale").build();
        AuditEvent event = AuditEvent.builder().eventId("evt-race").customerId("resistance")
                .type(EventType.AUTH_EVENT).timestamp(Instant.now()).build();

        String alertId = history.record(stale, event, List.of("email"));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM alert_history WHERE alert_id = ?", alertId);
        assertThat(row.get("rule_id")).isNull();
        assertThat(row.get("event_id")).isEqualTo("evt-race");
        assertThat(row.get("notified_channels")).isEqualTo("email");
    }

    @Test
    void seedingIsOncePerCustomerSoEditsAndDeletesSurviveARestart() {
        // what an operator does through the gateway API
        jdbc.update("UPDATE alert_rules SET enabled = false WHERE rule_id = 'pii-view'");
        jdbc.update("DELETE FROM alert_rules WHERE rule_id = 'login-failures'");

        // and what used to undo it: the next start re-upserting the file
        seeder.load();

        assertThat(jdbc.queryForObject(
                "SELECT enabled FROM alert_rules WHERE rule_id = 'pii-view'", Boolean.class))
                .as("a rule disabled through the API must stay disabled")
                .isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM alert_rules WHERE rule_id = 'login-failures'", Integer.class))
                .as("a rule deleted through the API must stay deleted")
                .isZero();
    }

    @Test
    void aRowWithAnUnknownEnumIsSkippedRatherThanEmptyingTheRuleSet() {
        jdbc.update("INSERT INTO alert_rules (rule_id, customer_id, name, event_type, enabled) "
                + "VALUES ('broken', 'resistance', 'Broken', 'NOT_A_REAL_TYPE', true)");

        rules.refresh();

        // the bad row is dropped; the good ones still load. Before, the row
        // mapper threw and the whole query failed, leaving no rules at all.
        assertThat(rules.rulesFor("resistance")).isNotEmpty();
        assertThat(rules.rulesFor("resistance")).extracting(AlertRule::getRuleId)
                .doesNotContain("broken");    }
}
