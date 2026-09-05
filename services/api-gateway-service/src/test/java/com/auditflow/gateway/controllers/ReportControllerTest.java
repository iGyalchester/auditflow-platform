package com.auditflow.gateway.controllers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reports end to end against a real Postgres.
 *
 * <p>This used to MockBean the repository, which meant the framework filter
 * - the thing the endpoint exists for - was whatever the stub returned. The
 * filter now runs in SQL, so a mock would be asserting on itself. Real rows,
 * real query, per the repo's no-mocking-internal-components rule.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class ReportControllerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("auditflow").withUsername("auditflow").withPassword("auditflow");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final Instant WINDOW_START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant IN_WINDOW = Instant.parse("2026-08-15T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM audit_events");
    }

    private void event(String id, String customer, String controls) {
        jdbc.update("INSERT INTO audit_events (event_id, customer_id, occurred_at, event_type, risk_level, controls) "
                + "VALUES (?, ?, ?, 'AUTH_EVENT', 'MEDIUM', ?)",
                id, customer, Timestamp.from(IN_WINDOW), controls);
    }

    @Test
    void generatesAFrameworkReportOverTheCustomersEventsInTheWindow() throws Exception {
        event("evt-soc2", "acme", "SOC2:AC-2");
        event("evt-gdpr", "acme", "GDPR:Art-30");
        event("evt-other-tenant", "other-co", "SOC2:AC-2");

        mockMvc.perform(get("/api/v1/reports/SOC2").header("X-Customer-Id", "acme")
                        .param("from", WINDOW_START.toString()).param("to", WINDOW_END.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        containsString("soc2-acme-2026-08-01.txt")))
                .andExpect(content().string(containsString("SOC 2 Evidence Report")))
                .andExpect(content().string(containsString("Customer: acme")))
                .andExpect(content().string(containsString("evt-soc2")))
                .andExpect(content().string(not(containsString("evt-gdpr"))))
                .andExpect(content().string(not(containsString("evt-other-tenant"))));
    }

    /**
     * A framework is either at the start of the controls string or after a
     * comma, and the two LIKE patterns have to cover both. A row whose only
     * SOC 2 control is the second one is exactly what a naive prefix match
     * drops silently from the evidence.
     */
    @Test
    void findsAFrameworkThatIsNotTheFirstControlOnTheEvent() throws Exception {
        event("evt-second", "acme", "GDPR:Art-32,SOC2:CC7.2");
        event("evt-first", "acme", "SOC2:CC6.1");
        event("evt-neither", "acme", "HIPAA:164.312");
        event("evt-none", "acme", null);

        mockMvc.perform(get("/api/v1/reports/soc2").header("X-Customer-Id", "acme")
                        .param("from", WINDOW_START.toString()).param("to", WINDOW_END.toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("evt-second")))
                .andExpect(content().string(containsString("evt-first")))
                .andExpect(content().string(not(containsString("evt-neither"))))
                .andExpect(content().string(not(containsString("evt-none"))));
    }

    /**
     * The point of pushing the filter into SQL. A tenant far past the cap in
     * total events, but with a handful for this framework, gets its report;
     * it used to be a 413 for a report fifty lines long.
     */
    @Test
    void theCapCountsTheFrameworksEventsNotEveryEventInTheWindow() throws Exception {
        for (int i = 0; i < ReportController.MAX_EVENTS + 50; i++) {
            event("noise-" + i, "acme", "HIPAA:164.312");
        }
        event("evt-soc2", "acme", "SOC2:AC-2");

        mockMvc.perform(get("/api/v1/reports/soc2").header("X-Customer-Id", "acme")
                        .param("from", WINDOW_START.toString()).param("to", WINDOW_END.toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("evt-soc2")));
    }

    @Test
    void aWindowOverTheCapIs413NotATruncatedReport() throws Exception {
        for (int i = 0; i < ReportController.MAX_EVENTS + 1; i++) {
            event("soc2-" + i, "acme", "SOC2:AC-2");
        }

        mockMvc.perform(get("/api/v1/reports/soc2").header("X-Customer-Id", "acme")
                        .param("from", WINDOW_START.toString()).param("to", WINDOW_END.toString()))
                .andExpect(status().isPayloadTooLarge());
    }

    /**
     * customerId reaches the filename and comes from a JWT claim whose shape
     * we do not control. A quote or a slash must not break out of the header.
     */
    @Test
    void theFilenameIsSafeForAnOddCustomerId() throws Exception {
        // a double quote and a slash: the two characters that would
        // otherwise break the header or the filename
        String odd = "we" + (char) 34 + "ird/co";
        event("evt-soc2", odd, "SOC2:AC-2");

        mockMvc.perform(get("/api/v1/reports/soc2").header("X-Customer-Id", odd)
                        .param("from", WINDOW_START.toString()).param("to", WINDOW_END.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        containsString("soc2-we_ird_co-2026-08-01.txt")));
    }

    @Test
    void listsFrameworksAndRejectsUnknownOnes() throws Exception {
        mockMvc.perform(get("/api/v1/reports").header("X-Customer-Id", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(contains("gdpr", "hipaa", "soc2")));
        mockMvc.perform(get("/api/v1/reports/pci").header("X-Customer-Id", "acme"))
                .andExpect(status().isNotFound());
    }

    @Test
    void invertedWindowIs400AndNoCustomerIs400() throws Exception {
        mockMvc.perform(get("/api/v1/reports/soc2").header("X-Customer-Id", "acme")
                        .param("from", WINDOW_END.toString()).param("to", WINDOW_START.toString()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/reports/soc2")).andExpect(status().isBadRequest());
    }
}
