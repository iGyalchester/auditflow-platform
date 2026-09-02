package com.auditflow.alerting.history;

import com.auditflow.alerting.rules.FileRuleRepository;
import com.auditflow.common.enums.EventType;
import com.auditflow.common.model.AlertRule;
import com.auditflow.common.model.AuditEvent;
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
    private FileRuleRepository rules;
    @Autowired
    private AlertHistoryWriter history;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void fileRulesAreSyncedAndHistoryRowsReferenceThem() {
        Integer ruleRows = jdbc.queryForObject("SELECT count(*) FROM alert_rules", Integer.class);
        assertThat(ruleRows).isEqualTo(3);
        Map<String, Object> pii = jdbc.queryForMap("SELECT * FROM alert_rules WHERE rule_id = 'pii-view'");
        assertThat(pii.get("notification_channels")).isEqualTo("slack,email");
        assertThat(pii.get("risk_threshold")).isEqualTo("HIGH");

        AlertRule rule = rules.rulesFor("resistance").get(0);
        AuditEvent event = AuditEvent.builder().eventId("evt-h1").customerId("resistance")
                .type(EventType.AUTH_EVENT).timestamp(Instant.now()).build();
        String alertId = history.record(rule, event, List.of("slack"));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM alert_history WHERE alert_id = ?", alertId);
        assertThat(row.get("rule_id")).isEqualTo(rule.getRuleId());
        assertThat(row.get("customer_id")).isEqualTo("resistance");
        assertThat(row.get("notified_channels")).isEqualTo("slack");
    }
}
