package com.auditflow.gateway.controllers;

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

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rule CRUD and tenant scoping against a real Postgres.
 *
 * <p>These used to run with a mocked repository, which meant the answers
 * came from the stub. That matters most for the PUT: it now decides 404 from
 * the upsert's row count rather than a preceding SELECT, and the reason a
 * foreign rule id counts zero is a WHERE clause in the SQL. A mock cannot
 * tell you whether that clause is right.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class AlertRuleControllerCrudTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("auditflow").withUsername("auditflow").withPassword("auditflow");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final String VALID = """
            {"name":"Failed login","eventType":"AUTH_EVENT",
             "conditionExpression":"action == 'LOGIN_FAILURE'","notificationChannels":["slack"]}
            """;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM alert_history");
        jdbc.update("DELETE FROM alert_rules");
    }

    private void ruleOwnedBy(String ruleId, String customerId) {
        jdbc.update("INSERT INTO alert_rules (rule_id, customer_id, name, enabled) VALUES (?, ?, 'existing', true)",
                ruleId, customerId);
    }

    @Test
    void createsARuleForTheCallingCustomerWithAServerGeneratedId() throws Exception {
        mockMvc.perform(post("/api/v1/alert-rules").header("X-Customer-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/alert-rules/")))
                .andExpect(jsonPath("$.customerId").value("acme"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.ruleId").isNotEmpty());

        mockMvc.perform(get("/api/v1/alert-rules").header("X-Customer-Id", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Failed login"))
                .andExpect(jsonPath("$[0].eventType").value("AUTH_EVENT"))
                .andExpect(jsonPath("$[0].notificationChannels[0]").value("slack"));
    }

    @Test
    void replaceKeepsTheIdAndCustomer() throws Exception {
        ruleOwnedBy("r1", "acme");

        mockMvc.perform(put("/api/v1/alert-rules/r1").header("X-Customer-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleId").value("r1"))
                .andExpect(jsonPath("$.name").value("Failed login"));

        mockMvc.perform(get("/api/v1/alert-rules/r1").header("X-Customer-Id", "acme"))
                .andExpect(jsonPath("$.name").value("Failed login"));
    }

    /**
     * The upsert's WHERE clause is what makes a foreign rule id a no-op
     * rather than a hijack. A zero row count is therefore "no such rule for
     * you", and the other tenant's row must be untouched afterwards.
     */
    @Test
    void anotherCustomersRuleCannotBeReplacedOrDeleted() throws Exception {
        ruleOwnedBy("r-other", "other-co");

        mockMvc.perform(put("/api/v1/alert-rules/r-other").header("X-Customer-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/alert-rules/r-other").header("X-Customer-Id", "acme"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/alert-rules/r-other").header("X-Customer-Id", "other-co"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("existing"));
    }

    @Test
    void aRuleThatDoesNotExistAtAllIsAlsoA404() throws Exception {
        mockMvc.perform(put("/api/v1/alert-rules/nope").header("X-Customer-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isNotFound());
    }

    @Test
    void listAndGetAreScopedAndDeleteIs204() throws Exception {
        ruleOwnedBy("r1", "acme");
        ruleOwnedBy("r-other", "other-co");

        mockMvc.perform(get("/api/v1/alert-rules").header("X-Customer-Id", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].ruleId").value("r1"));
        mockMvc.perform(get("/api/v1/alert-rules/r-other").header("X-Customer-Id", "acme"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/alert-rules/r1").header("X-Customer-Id", "acme"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/alert-rules/r1").header("X-Customer-Id", "acme"))
                .andExpect(status().isNotFound());
    }
}
