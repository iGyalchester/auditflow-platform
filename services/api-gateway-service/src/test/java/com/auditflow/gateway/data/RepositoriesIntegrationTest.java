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
import java.util.List;

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


    /**
     * Both list endpoints filter by tenant and sort by time. With only a
     * single-column customer index Postgres finds the tenant's rows and then
     * sorts all of them to answer a 50-row page - work that grows with the
     * tenant's history rather than the page size. A composite
     * (customer_id, <time> DESC) is already in that order, so the plan reads
     * the index and stops at the limit.
     *
     * <p>seqscan is disabled for the check because these tables hold a
     * handful of rows here and a sequential scan is genuinely cheaper at
     * that size; the question is which index the planner reaches for when it
     * uses one at all. The assertion that matters is the absence of a Sort
     * node - that is the cost this index removes.
     */
    @Test
    void theListQueriesReadTheCompositeIndexInOrderRatherThanSorting() {
        // Several tenants, interleaved in time. One tenant is not enough to
        // make this test mean anything: with a single customer the planner
        // walks the global occurred_at index backwards and filters, which
        // costs nothing because every row matches. The composite only earns
        // its place when most rows belong to somebody else - which is the
        // real shape of a multi-tenant table.
        Instant now = Instant.now();
        String[] tenants = {"acme", "other-co", "third-co", "fourth-co", "fifth-co"};
        for (int i = 0; i < 500; i++) {
            String tenant = tenants[i % tenants.length];
            Instant at = now.minus(Duration.ofMinutes(i));
            event("evt-" + i, tenant, "AUTH_EVENT", at);
            jdbc.update("INSERT INTO alert_history (alert_id, event_id, customer_id, triggered_at, "
                    + "notified_channels) VALUES (?, ?, ?, ?, 'slack')",
                    "al-" + i, "evt-" + i, tenant, Timestamp.from(at));
        }
        // the planner needs statistics before it will prefer an index
        jdbc.execute("ANALYZE audit_events");
        jdbc.execute("ANALYZE alert_history");

        String auditPlan = explain("""
                SELECT event_id FROM audit_events WHERE customer_id = 'acme'
                ORDER BY occurred_at DESC LIMIT 50""");
        assertThat(auditPlan).as(auditPlan).contains("idx_audit_events_customer_occurred");
        assertThat(auditPlan)
                .as("the index is already in the requested order, so nothing needs sorting")
                .doesNotContain("Sort");

        String alertPlan = explain("""
                SELECT alert_id FROM alert_history WHERE customer_id = 'acme'
                ORDER BY triggered_at DESC LIMIT 50""");
        assertThat(alertPlan).as(alertPlan).contains("idx_alert_history_customer_triggered");
        assertThat(alertPlan).doesNotContain("Sort");
    }

    /**
     * The primary key is (customer_id, event_id), so its index already leads
     * with customer_id. A separate single-column index on customer_id is
     * dead weight paid for on every insert.
     */
    @Test
    void theRedundantSingleColumnCustomerIndexesAreGone() {
        assertThat(indexNames("audit_events"))
                .doesNotContain("idx_audit_events_customer_id")
                .contains("idx_audit_events_customer_occurred")
                .as("RetentionPurgeJob deletes by time across all tenants")
                .contains("idx_audit_events_occurred_at");

        assertThat(indexNames("alert_history"))
                .doesNotContain("idx_alert_history_customer_id")
                .contains("idx_alert_history_customer_triggered")
                .as("P7 added this for ON DELETE SET NULL")
                .contains("idx_alert_history_rule_id");
    }

    /**
     * seqscan is turned off around the EXPLAIN because these tables hold a
     * handful of rows and a sequential scan is genuinely cheaper at that
     * size; the question is which index the planner reaches for when it uses
     * one at all. It is restored before the connection goes back to the
     * pool, or every later test would run with a distorted planner.
     */
    private String explain(String sql) {
        return jdbc.execute((java.sql.Connection connection) -> {
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute("SET enable_seqscan = off");
                try (java.sql.ResultSet rs = statement.executeQuery("EXPLAIN (FORMAT JSON) " + sql)) {
                    rs.next();
                    return rs.getString(1);
                } finally {
                    statement.execute("SET enable_seqscan = on");
                }
            }
        });
    }

    private List<String> indexNames(String table) {
        return jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = ?",
                String.class, table);
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