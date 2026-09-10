package com.auditflow.gateway.controllers;

import com.auditflow.gateway.security.CurrentCustomer;
import com.auditflow.gateway.security.Roles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The console's read API end to end: real security chain, real
 * controllers, real SQL against a real Postgres. The {@code @WebMvcTest}
 * suites are the fast signal for each controller's contract; this is the
 * proof that the seams between them and the repositories hold - the
 * arguments a controller passes, the rows the SQL populates, the JSON the
 * console reads - per the repo's rule that persistence is tested against
 * the real thing, not a stub that agrees with its caller by construction.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class ConsoleReadApiIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("auditflow").withUsername("auditflow").withPassword("auditflow");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-08T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM alert_history");
        jdbc.update("DELETE FROM alert_rules");
        jdbc.update("DELETE FROM audit_events");
        jdbc.update("DELETE FROM customers");
        jdbc.update("INSERT INTO customers (customer_id, name) VALUES ('acme', 'Acme Corp')");
        event("e-1", "acme", "2026-09-02T10:00:00Z", "AUTH_EVENT", "boris", "login", "LOGIN_FAILURE", "MEDIUM", false, "SOC2:AC-2");
        event("e-2", "acme", "2026-09-03T10:00:00Z", "DATA_EXPORT", "dana", "customers_table", "EXPORT", "CRITICAL", true, "GDPR:Art-30");
        event("e-3", "acme", "2026-09-04T10:00:00Z", "AUTH_EVENT", "boris", "login", "LOGIN_FAILURE", "HIGH", false, "SOC2:AC-2");
        event("e-other", "other-co", "2026-09-04T10:00:00Z", "AUTH_EVENT", "x", "login", "LOGIN_FAILURE", "MEDIUM", false, null);
        jdbc.update("INSERT INTO alert_rules (rule_id, customer_id, name, notification_channels) VALUES ('r-1', 'acme', 'Failed login', 'slack,email')");
        jdbc.update("INSERT INTO alert_history (alert_id, rule_id, event_id, customer_id, triggered_at, notified_channels) "
                + "VALUES ('al-1', 'r-1', 'e-3', 'acme', ?, 'slack')", Timestamp.from(Instant.parse("2026-09-04T10:00:01Z")));
    }

    private void event(String id, String customer, String at, String type, String user, String resource, String action,
                       String risk, boolean anomalous, String controls) {
        jdbc.update("INSERT INTO audit_events (event_id, customer_id, occurred_at, event_type, user_id, resource, action, "
                + "risk_level, anomalous, controls) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, customer, Timestamp.from(Instant.parse(at)), type, user, resource, action, risk, anomalous, controls);
    }

    @Test
    void theDashboardStatsComeOutOfTheDatabaseAsTheConsoleReadsThem() throws Exception {
        mockMvc.perform(get("/api/v1/stats").header(CurrentCustomer.DEV_HEADER, "acme")
                        .param("from", FROM.toString()).param("to", TO.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.events").value(3))
                .andExpect(jsonPath("$.totals.alerts").value(1))
                .andExpect(jsonPath("$.totals.critical").value(1))
                .andExpect(jsonPath("$.totals.anomalous").value(1))
                .andExpect(jsonPath("$.totals.users").value(2))
                .andExpect(jsonPath("$.perDay.length()").value(7))
                .andExpect(jsonPath("$.perDay[1].day").value("2026-09-02"))
                .andExpect(jsonPath("$.perDay[1].byRisk.MEDIUM").value(1))
                .andExpect(jsonPath("$.byType.AUTH_EVENT").value(2))
                .andExpect(jsonPath("$.byControl['SOC2:AC-2']").value(2))
                .andExpect(jsonPath("$.topUsers[0].name").value("boris"));
    }

    @Test
    void theExplorerFiltersDetailAndAlertsAreScopedToTheCaller() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs").header(CurrentCustomer.DEV_HEADER, "acme")
                        .param("q", "CUSTOM").param("from", FROM.toString()).param("to", TO.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventId").value("e-2"));
        mockMvc.perform(get("/api/v1/audit-logs/e-3").header(CurrentCustomer.DEV_HEADER, "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event.riskLevel").value("HIGH"))
                .andExpect(jsonPath("$.alerts[0].ruleName").value("Failed login"))
                .andExpect(jsonPath("$.alerts[0].ruleChannels").value("slack,email"));
        mockMvc.perform(get("/api/v1/audit-logs/e-other").header(CurrentCustomer.DEV_HEADER, "acme"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/alerts/al-1").header(CurrentCustomer.DEV_HEADER, "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event.eventId").value("e-3"))
                .andExpect(jsonPath("$.undeliveredChannels").value(contains("email")));
    }

    @Test
    void aDryRunCountsAgainstRealRowsWithTheCriteriaInSql() throws Exception {
        mockMvc.perform(post("/api/v1/alert-rules/dry-run").header(CurrentCustomer.DEV_HEADER, "acme")
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"AUTH_EVENT\",\"riskThreshold\":\"HIGH\",\"conditionExpression\":\"action == 'LOGIN_FAILURE'\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanned").value(3))
                .andExpect(jsonPath("$.matched").value(1))
                .andExpect(jsonPath("$.sample[0].eventId").value("e-3"));
        mockMvc.perform(post("/api/v1/alert-rules/dry-run").header(CurrentCustomer.DEV_HEADER, "acme")
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"riskThreshold\":\"MEDIUM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanned").value(3))
                .andExpect(jsonPath("$.matched").value(3));
    }

    @Test
    void theOperatorViewSeesEveryTenantAndOnlyOperatorsSeeIt() throws Exception {
        mockMvc.perform(get("/api/v1/operator/customers").header(CurrentCustomer.DEV_HEADER, "platform")
                        .header(Roles.DEV_ROLES_HEADER, "operator"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].customerId").value(contains("acme", "other-co")))
                .andExpect(jsonPath("$[0].name").value("Acme Corp"))
                .andExpect(jsonPath("$[0].rules").value(1));
        mockMvc.perform(get("/api/v1/operator/customers").header(CurrentCustomer.DEV_HEADER, "acme"))
                .andExpect(status().isForbidden());
        // an operator viewing as acme reads acme's numbers
        mockMvc.perform(get("/api/v1/stats").header(CurrentCustomer.DEV_HEADER, "platform")
                        .header(Roles.DEV_ROLES_HEADER, "operator").header(RequestScope.ACTING_HEADER, "acme")
                        .param("from", FROM.toString()).param("to", TO.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.events").value(3));
    }

    /** Unused-window guard: the seed's dates stay inside the window this class asks for. */
    @Test
    void seedIsInsideTheWindow() {
        org.assertj.core.api.Assertions.assertThat(Duration.between(FROM, TO)).isEqualTo(Duration.ofDays(7));
    }
}
