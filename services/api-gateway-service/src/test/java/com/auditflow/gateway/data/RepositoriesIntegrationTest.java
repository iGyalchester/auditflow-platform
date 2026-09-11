package com.auditflow.gateway.data;

import com.auditflow.gateway.api.OperatorCustomer;
import com.auditflow.gateway.api.PlatformStats;
import com.auditflow.gateway.api.Stats;
import com.auditflow.gateway.data.AlertHistoryRepository.AlertFilter;
import com.auditflow.gateway.data.AuditLogRepository.AuditLogFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private CustomerRepository customers;
    @Autowired
    private StatsRepository stats;
    @Autowired
    private OperatorRepository operator;
    @Autowired
    private JdbcTemplate jdbc;

    /** One container for the class: every test starts from empty tables (FK order matters). */
    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM alert_history");
        jdbc.update("DELETE FROM alert_rules");
        jdbc.update("DELETE FROM audit_events");
        jdbc.update("DELETE FROM customers");
    }

    @Test
    void customerNamesComeFromTheCustomersTable() {
        jdbc.update("INSERT INTO customers (customer_id, name) VALUES ('acme', 'Acme Corp')");
        assertThat(customers.findName("acme")).contains("Acme Corp");
        assertThat(customers.findName("nobody")).isEmpty();
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

        assertThat(auditLogs.find("acme", AuditLogFilter.NONE, 100)).extracting(AuditLogRepository.AuditLogRow::eventId)
                .containsExactly("a-new", "a-file", "a-old");
        assertThat(auditLogs.find("acme", new AuditLogFilter("AUTH_EVENT", null, null, null, null, null, null), 100)).extracting(AuditLogRepository.AuditLogRow::eventId)
                .containsExactly("a-new", "a-old");
        assertThat(auditLogs.find("acme", AuditLogFilter.window(now.minus(Duration.ofDays(1)), null), 100))
                .extracting(AuditLogRepository.AuditLogRow::eventId).containsExactly("a-new", "a-file");
        assertThat(auditLogs.find("acme", AuditLogFilter.NONE, 1)).hasSize(1);
        assertThat(auditLogs.find("nobody", AuditLogFilter.NONE, 100)).isEmpty();
        // other-co's row never appears in acme's results
        assertThat(auditLogs.find("acme", AuditLogFilter.NONE, 100)).extracting(AuditLogRepository.AuditLogRow::eventId)
                .doesNotContain("b-1");
    }

    @Test
    void alertsJoinTheRuleNameAndAreScoped() {
        jdbc.update("INSERT INTO alert_rules (rule_id, customer_id, name) VALUES ('r-acme', 'acme', 'Failed login')");
        jdbc.update("INSERT INTO alert_rules (rule_id, customer_id, name) VALUES ('r-other', 'other-co', 'Other')");
        jdbc.update("INSERT INTO alert_history (alert_id, rule_id, event_id, customer_id, notified_channels) VALUES ('al-1', 'r-acme', 'a-new', 'acme', 'slack')");
        jdbc.update("INSERT INTO alert_history (alert_id, rule_id, event_id, customer_id, notified_channels) VALUES ('al-2', 'r-other', 'b-1', 'other-co', 'email')");

        var rows = alerts.find("acme", AlertFilter.NONE, 100);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).ruleName()).isEqualTo("Failed login");
        assertThat(rows.get(0).notifiedChannels()).isEqualTo("slack");
        assertThat(alerts.find("other-co", AlertFilter.NONE, 100)).extracting(AlertHistoryRepository.AlertRow::alertId).containsExactly("al-2");
    }

    private void event(String id, String customer, String type, Instant at) {
        jdbc.update("INSERT INTO audit_events (event_id, customer_id, occurred_at, event_type, controls) VALUES (?, ?, ?, ?, ?)",
                id, customer, Timestamp.from(at), type, "SOC2:AC-2");
    }

    private void richEvent(String id, String customer, Instant at, String type, String user, String resource,
                           String action, String risk, boolean anomalous, String controls) {
        jdbc.update("INSERT INTO audit_events (event_id, customer_id, occurred_at, event_type, user_id, resource, "
                + "action, risk_level, anomalous, controls) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, customer, Timestamp.from(at), type, user, resource, action, risk, anomalous, controls);
    }

    @Test
    void explorerFiltersNarrowTheCustomersEventsOnly() {
        Instant now = Instant.now();
        richEvent("f-1", "acme", now.minus(Duration.ofHours(1)), "AUTH_EVENT", "boris", "login", "LOGIN_FAILURE", "MEDIUM", false, "SOC2:AC-2");
        richEvent("f-2", "acme", now.minus(Duration.ofHours(2)), "DATA_EXPORT", "dana", "customers_table", "EXPORT", "CRITICAL", true, "GDPR:Art-30");
        richEvent("f-3", "acme", now.minus(Duration.ofHours(3)), "AUTH_EVENT", "boris", "login", "LOGIN_SUCCESS", "LOW", false, null);
        richEvent("f-other", "other-co", now, "AUTH_EVENT", "boris", "login", "LOGIN_FAILURE", "MEDIUM", false, null);

        assertThat(auditLogs.find("acme", new AuditLogFilter(null, "CRITICAL", null, null, null, null, null), 100))
                .extracting(AuditLogRepository.AuditLogRow::eventId).containsExactly("f-2");
        assertThat(auditLogs.find("acme", new AuditLogFilter(null, null, "boris", null, null, null, null), 100))
                .extracting(AuditLogRepository.AuditLogRow::eventId).containsExactly("f-1", "f-3");
        assertThat(auditLogs.find("acme", new AuditLogFilter(null, null, null, true, null, null, null), 100))
                .extracting(AuditLogRepository.AuditLogRow::eventId).containsExactly("f-2");
        // q is case-insensitive over resource and action, and a literal "%" stays literal
        assertThat(auditLogs.find("acme", new AuditLogFilter(null, null, null, null, "FAIL", null, null), 100))
                .extracting(AuditLogRepository.AuditLogRow::eventId).containsExactly("f-1");
        assertThat(auditLogs.find("acme", new AuditLogFilter(null, null, null, null, "custom", null, null), 100))
                .extracting(AuditLogRepository.AuditLogRow::eventId).containsExactly("f-2");
        assertThat(auditLogs.find("acme", new AuditLogFilter(null, null, null, null, "%", null, null), 100)).isEmpty();
        // keyset paging: "to" is exclusive, so the oldest row seen is not repeated
        assertThat(auditLogs.find("acme", AuditLogFilter.window(null, now.minus(Duration.ofHours(1))), 100))
                .extracting(AuditLogRepository.AuditLogRow::eventId).containsExactly("f-2", "f-3");

        assertThat(auditLogs.findOne("acme", "f-2")).isPresent();
        assertThat(auditLogs.findOne("acme", "f-2").get().controls()).isEqualTo("GDPR:Art-30");
        assertThat(auditLogs.findOne("acme", "f-other")).isEmpty();
        assertThat(auditLogs.findEvents("acme", now.minus(Duration.ofDays(1)), now, null, null, 100))
                .extracting(com.auditflow.common.model.AuditEvent::getEventId).containsExactly("f-3", "f-2", "f-1");
        // the dry run's cheap criteria run in SQL: type, and risk at or above a threshold
        assertThat(auditLogs.findEvents("acme", now.minus(Duration.ofDays(1)), now,
                com.auditflow.common.enums.EventType.AUTH_EVENT, com.auditflow.common.enums.RiskLevel.MEDIUM, 100))
                .extracting(com.auditflow.common.model.AuditEvent::getEventId).containsExactly("f-1");
        assertThat(auditLogs.countEvents("acme", now.minus(Duration.ofDays(1)), now, null, null)).isEqualTo(3);
        assertThat(auditLogs.countEvents("acme", now.minus(Duration.ofDays(1)), now, null,
                com.auditflow.common.enums.RiskLevel.HIGH)).isEqualTo(1);
    }

    @Test
    void alertFiltersDetailAndPerEventLookupAreScoped() {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO alert_rules (rule_id, customer_id, name, notification_channels) VALUES ('r-a', 'acme', 'Failed login', 'slack,email')");
        jdbc.update("INSERT INTO alert_rules (rule_id, customer_id, name) VALUES ('r-b', 'acme', 'Exports')");
        jdbc.update("INSERT INTO alert_history (alert_id, rule_id, event_id, customer_id, triggered_at, notified_channels) VALUES ('al-1', 'r-a', 'e-1', 'acme', ?, 'slack')", Timestamp.from(now.minus(Duration.ofHours(1))));
        jdbc.update("INSERT INTO alert_history (alert_id, rule_id, event_id, customer_id, triggered_at, notified_channels) VALUES ('al-2', 'r-b', 'e-2', 'acme', ?, 'slack')", Timestamp.from(now.minus(Duration.ofDays(2))));
        jdbc.update("INSERT INTO alert_history (alert_id, rule_id, event_id, customer_id, triggered_at, notified_channels) VALUES ('al-3', 'r-a', 'e-1', 'acme', ?, 'email')", Timestamp.from(now.minus(Duration.ofDays(3))));
        jdbc.update("INSERT INTO alert_history (alert_id, event_id, customer_id, triggered_at, notified_channels) VALUES ('al-x', 'e-1', 'other-co', ?, 'slack')", Timestamp.from(now));

        assertThat(alerts.find("acme", new AlertFilter("r-a", null, null), 100))
                .extracting(AlertHistoryRepository.AlertRow::alertId).containsExactly("al-1", "al-3");
        assertThat(alerts.find("acme", new AlertFilter(null, now.minus(Duration.ofDays(1)), null), 100))
                .extracting(AlertHistoryRepository.AlertRow::alertId).containsExactly("al-1");
        assertThat(alerts.find("acme", new AlertFilter(null, null, now.minus(Duration.ofDays(1))), 100))
                .extracting(AlertHistoryRepository.AlertRow::alertId).containsExactly("al-2", "al-3");

        var detail = alerts.findOne("acme", "al-1").orElseThrow();
        assertThat(detail.ruleChannels()).isEqualTo("slack,email");
        assertThat(detail.notifiedChannels()).isEqualTo("slack");
        assertThat(alerts.findOne("acme", "al-x")).isEmpty();
        assertThat(alerts.findForEvent("acme", "e-1"))
                .extracting(AlertHistoryRepository.AlertRow::alertId).containsExactly("al-1", "al-3");
    }

    @Test
    void statsCountOneCustomerOverOneWindowWithZeroFilledDays() {
        Instant to = Instant.parse("2026-09-04T00:00:00Z");
        Instant from = to.minus(Duration.ofDays(3));   // Sep 1, 2, 3
        richEvent("s-1", "acme", Instant.parse("2026-09-01T10:00:00Z"), "AUTH_EVENT", "boris", "login", "LOGIN_FAILURE", "MEDIUM", false, "SOC2:AC-2,SOC2:IA-2");
        richEvent("s-2", "acme", Instant.parse("2026-09-01T11:00:00Z"), "AUTH_EVENT", "dana", "login", "LOGIN_FAILURE", "CRITICAL", true, "SOC2:AC-2");
        richEvent("s-3", "acme", Instant.parse("2026-09-03T23:59:59Z"), "DATA_EXPORT", "boris", "customers_table", "EXPORT", "HIGH", false, "GDPR:Art-30");
        richEvent("s-prev", "acme", Instant.parse("2026-08-30T10:00:00Z"), "AUTH_EVENT", "boris", "login", "LOGIN_FAILURE", "LOW", false, null);
        richEvent("s-out", "acme", to, "AUTH_EVENT", "boris", "login", "LOGIN_FAILURE", "LOW", false, null);
        richEvent("s-other", "other-co", Instant.parse("2026-09-02T10:00:00Z"), "AUTH_EVENT", "x", "login", "LOGIN_FAILURE", "CRITICAL", true, "SOC2:AC-2");
        jdbc.update("INSERT INTO alert_history (alert_id, event_id, customer_id, triggered_at, notified_channels) VALUES ('st-1', 's-2', 'acme', ?, 'slack')", Timestamp.from(Instant.parse("2026-09-01T11:00:01Z")));
        jdbc.update("INSERT INTO alert_history (alert_id, event_id, customer_id, triggered_at, notified_channels) VALUES ('st-x', 's-other', 'other-co', ?, 'slack')", Timestamp.from(Instant.parse("2026-09-02T10:00:01Z")));

        Stats s = stats.stats("acme", new com.auditflow.gateway.controllers.TimeWindow(from, to));

        assertThat(s.totals()).isEqualTo(new Stats.Totals(3, 1, 1, 1, 2));
        assertThat(s.previous()).isEqualTo(new Stats.Totals(1, 0, 0, 0, 1));
        assertThat(s.perDay()).hasSize(3);
        assertThat(s.perDay().get(0).day()).isEqualTo(java.time.LocalDate.of(2026, 9, 1));
        assertThat(s.perDay().get(0).events()).isEqualTo(2);
        assertThat(s.perDay().get(0).alerts()).isEqualTo(1);
        assertThat(s.perDay().get(0).byRisk()).containsEntry("MEDIUM", 1L).containsEntry("CRITICAL", 1L).containsEntry("LOW", 0L);
        assertThat(s.perDay().get(1).events()).isZero();
        assertThat(s.perDay().get(2).byRisk()).containsEntry("HIGH", 1L);
        assertThat(s.byType()).containsExactly(java.util.Map.entry("AUTH_EVENT", 2L), java.util.Map.entry("DATA_EXPORT", 1L));
        assertThat(s.byRisk()).containsKeys("LOW", "MEDIUM", "HIGH", "CRITICAL").containsEntry("LOW", 0L);
        assertThat(s.byControl()).containsExactly(java.util.Map.entry("SOC2:AC-2", 2L),
                java.util.Map.entry("GDPR:Art-30", 1L), java.util.Map.entry("SOC2:IA-2", 1L));
        assertThat(s.topUsers()).containsExactly(new Stats.NameCount("boris", 2), new Stats.NameCount("dana", 1));
        assertThat(s.topResources().get(0)).isEqualTo(new Stats.NameCount("login", 2));
    }

    @Test
    void operatorViewsSeeEveryTenantIncludingUnregisteredOnes() {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO customers (customer_id, name) VALUES ('acme', 'Acme Corp')");
        jdbc.update("INSERT INTO customers (customer_id, name) VALUES ('quiet', 'Quiet Co')");
        jdbc.update("INSERT INTO alert_rules (rule_id, customer_id, name) VALUES ('r-a', 'acme', 'A')");
        event("o-1", "acme", "AUTH_EVENT", now.minus(Duration.ofHours(2)));
        event("o-2", "acme", "AUTH_EVENT", now.minus(Duration.ofDays(3)));
        event("o-3", "acme", "AUTH_EVENT", now.minus(Duration.ofDays(30)));
        event("o-r", "resistance", "AUTH_EVENT", now.minus(Duration.ofHours(1)));
        jdbc.update("INSERT INTO alert_history (alert_id, rule_id, event_id, customer_id, triggered_at, notified_channels) VALUES ('oa-1', 'r-a', 'o-1', 'acme', ?, 'slack')", Timestamp.from(now.minus(Duration.ofHours(2))));

        List<OperatorCustomer> rows = operator.customers(now);
        assertThat(rows).extracting(OperatorCustomer::customerId).containsExactly("acme", "resistance", "quiet");
        OperatorCustomer acme = rows.get(0);
        assertThat(acme.name()).isEqualTo("Acme Corp");
        assertThat(acme.events24h()).isEqualTo(1);
        assertThat(acme.events7d()).isEqualTo(2);
        assertThat(acme.alerts7d()).isEqualTo(1);
        assertThat(acme.rules()).isEqualTo(1);
        assertThat(acme.lastEventAt()).isNotNull();
        OperatorCustomer resistance = rows.get(1);
        assertThat(resistance.name()).isNull();
        assertThat(resistance.events24h()).isEqualTo(1);
        OperatorCustomer quiet = rows.get(2);
        assertThat(quiet.events7d()).isZero();
        assertThat(quiet.lastEventAt()).isNull();

        PlatformStats platform = operator.stats(now.minus(Duration.ofDays(7)), now);
        assertThat(platform.totals().events()).isEqualTo(3);
        assertThat(platform.totals().alerts()).isEqualTo(1);
        assertThat(platform.totals().customers()).isEqualTo(2);
        assertThat(platform.perDay()).hasSizeBetween(7, 8);
        assertThat(platform.perDay().stream().mapToLong(PlatformStats.DayBucket::events).sum()).isEqualTo(3);
        assertThat(platform.topCustomers().get(0)).isEqualTo(new PlatformStats.CustomerCount("acme", "Acme Corp", 2, 1));
        assertThat(platform.byCustomer()).containsEntry("acme", "Acme Corp").containsEntry("resistance", null);
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

        var events = auditLogs.findForReport("acme", "SOC2", now.minus(Duration.ofDays(30)), now, 100);

        assertThat(events).extracting(com.auditflow.common.model.AuditEvent::getEventId).contains("r-in").doesNotContain("r-out");
        var inWindow = events.stream().filter(e -> e.getEventId().equals("r-in")).findFirst().orElseThrow();
        assertThat(inWindow.getControls()).extracting(com.auditflow.common.model.ComplianceControl::getControlId).containsExactly("AC-2");
        assertThat(inWindow.getType()).isEqualTo(com.auditflow.common.enums.EventType.DATA_EXPORT);
    }

    /**
     * The framework filter runs in SQL so the caller's cap counts the rows
     * the report will contain. controls is
     * "FRAMEWORK:CONTROL,FRAMEWORK:CONTROL", so the framework is either at
     * the start or after a comma - a row whose only SOC 2 control is the
     * second one is what a prefix-only match drops from the evidence.
     */
    @Test
    void findForReportReturnsOnlyEventsClassifiedForTheFramework() {
        Instant now = Instant.now();
        Instant at = now.minus(Duration.ofDays(1));
        jdbc.update("INSERT INTO audit_events (event_id, customer_id, occurred_at, event_type, controls) "
                + "VALUES ('c-first', 'acme', ?, 'AUTH_EVENT', 'SOC2:CC6.1')", Timestamp.from(at));
        jdbc.update("INSERT INTO audit_events (event_id, customer_id, occurred_at, event_type, controls) "
                + "VALUES ('c-second', 'acme', ?, 'AUTH_EVENT', 'GDPR:Art32,SOC2:CC7.2')", Timestamp.from(at));
        jdbc.update("INSERT INTO audit_events (event_id, customer_id, occurred_at, event_type, controls) "
                + "VALUES ('c-other', 'acme', ?, 'AUTH_EVENT', 'HIPAA:164.312')", Timestamp.from(at));
        jdbc.update("INSERT INTO audit_events (event_id, customer_id, occurred_at, event_type, controls) "
                + "VALUES ('c-none', 'acme', ?, 'AUTH_EVENT', NULL)", Timestamp.from(at));

        assertThat(auditLogs.findForReport("acme", "SOC2", now.minus(Duration.ofDays(30)), now, 100))
                .extracting(com.auditflow.common.model.AuditEvent::getEventId)
                .containsExactlyInAnyOrder("c-first", "c-second");
    }

    @Test
    void anAlertOutlivesTheRuleThatRaisedIt() {
        rules.upsert(com.auditflow.common.model.AlertRule.builder()
                .ruleId("doomed").customerId("acme").name("Doomed").build());
        jdbc.update("INSERT INTO alert_history (alert_id, rule_id, event_id, customer_id, notified_channels) "
                + "VALUES ('al-1', 'doomed', 'evt-1', 'acme', 'slack')");

        // used to fail on the foreign key, making the rule undeletable
        assertThat(rules.delete("acme", "doomed")).isTrue();

        var listed = alerts.find("acme", AlertFilter.NONE, 10);
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).alertId()).isEqualTo("al-1");
        // the LEFT JOIN already handled this: no rule, no attribution, but
        // the alert is still on the record
        assertThat(listed.get(0).ruleId()).isNull();
        assertThat(listed.get(0).ruleName()).isNull();
    }

    /**
     * Every service migrates on boot, so migrate() meets a database that is
     * already at the latest version. Under spring.sql.init that meant every
     * statement had to stay idempotent forever; Flyway records what it has
     * applied, so a second call is a no-op it can prove.
     */
    @Test
    void migrateIsIdempotent() {
        Flyway flyway = Flyway.configure()
                .dataSource(jdbc.getDataSource())
                .locations("classpath:db/migration")
                .load();

        assertThat(flyway.migrate().migrationsExecuted)
                .as("the application already migrated on startup")
                .isZero();

        // and the schema is still the shape the code expects
        jdbc.update("INSERT INTO alert_history (alert_id, rule_id, event_id, customer_id) "
                + "VALUES ('al-2', NULL, 'evt-2', 'acme')");
        assertThat(alerts.find("acme", AlertFilter.NONE, 10)).hasSize(1);
    }

    @Test
    void theSchemaHistoryRecordsExactlyOneRowPerVersion() {
        assertThat(jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class))
                .doesNotHaveDuplicates()
                .contains("1");
    }
}