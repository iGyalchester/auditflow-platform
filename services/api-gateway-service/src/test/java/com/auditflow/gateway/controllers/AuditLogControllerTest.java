package com.auditflow.gateway.controllers;

import com.auditflow.gateway.data.AlertHistoryRepository;
import com.auditflow.gateway.data.AlertHistoryRepository.AlertRow;
import com.auditflow.gateway.data.AuditLogRepository;
import com.auditflow.gateway.data.AuditLogRepository.AuditLogFilter;
import com.auditflow.gateway.data.AuditLogRepository.AuditLogRow;
import com.auditflow.gateway.security.CurrentCustomer;
import com.auditflow.gateway.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Auth disabled (default profile): the customer comes from X-Customer-Id. */
@WebMvcTest(AuditLogController.class)
@Import({SecurityConfig.class, CurrentCustomer.class, RequestScope.class})
class AuditLogControllerTest {

    private static final AuditLogRow ROW = new AuditLogRow("evt-1", "boris", null, Instant.parse("2026-09-02T10:00:00Z"),
            "AUTH_EVENT", "login", "LOGIN_FAILURE", "MEDIUM", false, "SOC2:AC-2,SOC2:IA-2");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuditLogRepository repository;
    @MockBean
    private AlertHistoryRepository alerts;

    @Test
    void listsTheHeaderCustomersEventsWithFilters() throws Exception {
        AuditLogFilter expected = new AuditLogFilter("AUTH_EVENT", null, null, null, null,
                Instant.parse("2026-09-01T00:00:00Z"), null);
        when(repository.find("acme", expected, 50)).thenReturn(List.of(ROW));

        mockMvc.perform(get("/api/v1/audit-logs")
                        .header("X-Customer-Id", "acme")
                        .param("type", "AUTH_EVENT").param("from", "2026-09-01T00:00:00Z").param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId").value("evt-1"))
                .andExpect(jsonPath("$[0].action").value("LOGIN_FAILURE"))
                .andExpect(jsonPath("$[0].occurredAt").value("2026-09-02T10:00:00Z"))
                .andExpect(jsonPath("$[0].controls").value("SOC2:AC-2,SOC2:IA-2"));
    }

    @Test
    void everyExplorerFilterReachesTheQueryNormalised() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs").header("X-Customer-Id", "acme")
                        .param("type", "data_export").param("riskLevel", "critical").param("userId", " dana ")
                        .param("anomalous", "true").param("q", "  customers ")
                        .param("from", "2026-09-01T00:00:00Z").param("to", "2026-09-02T00:00:00Z"))
                .andExpect(status().isOk());

        verify(repository).find("acme", new AuditLogFilter("DATA_EXPORT", "CRITICAL", "dana", true, "customers",
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-02T00:00:00Z")), 100);
    }

    @Test
    void defaultsToOneHundredRowsAndNoFilters() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs").header("X-Customer-Id", "acme").param("q", "  "))
                .andExpect(status().isOk());

        verify(repository).find("acme", AuditLogFilter.NONE, 100);
    }

    @Test
    void unknownTypeOrRiskIs400NamingTheChoices() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs").header("X-Customer-Id", "acme").param("type", "LOGIN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("AUTH_EVENT")));
        mockMvc.perform(get("/api/v1/audit-logs").header("X-Customer-Id", "acme").param("riskLevel", "SEVERE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("CRITICAL")));
        verifyNoInteractions(repository);
    }

    @Test
    void overlongSearchAndInvertedWindowAre400() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs").header("X-Customer-Id", "acme").param("q", "x".repeat(101)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/audit-logs").header("X-Customer-Id", "acme")
                        .param("from", "2026-09-02T00:00:00Z").param("to", "2026-09-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("from must be before to"));
        verifyNoInteractions(repository);
    }

    @Test
    void detailIsTheRowPlusItsAlertsAnd404OtherwiseEvenForAnotherTenantsEvent() throws Exception {
        when(repository.findOne("acme", "evt-1")).thenReturn(Optional.of(ROW));
        when(alerts.findForEvent("acme", "evt-1")).thenReturn(List.of(
                new AlertRow("al-1", "r-1", "Failed login", "evt-1", Instant.parse("2026-09-02T10:00:05Z"), "slack", "slack,email")));

        mockMvc.perform(get("/api/v1/audit-logs/evt-1").header("X-Customer-Id", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event.eventId").value("evt-1"))
                .andExpect(jsonPath("$.alerts[0].alertId").value("al-1"))
                .andExpect(jsonPath("$.alerts[0].ruleName").value("Failed login"));

        mockMvc.perform(get("/api/v1/audit-logs/evt-1").header("X-Customer-Id", "other-co"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        verify(alerts, never()).findForEvent(eq("other-co"), any());
    }

    @Test
    void noCustomerIs400AndNeverQueries() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/audit-logs/evt-1")).andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
    }

    @Test
    void limitOutOfRangeIs400() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs").header("X-Customer-Id", "acme").param("limit", "5000"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/audit-logs").header("X-Customer-Id", "acme").param("limit", "0"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
    }

    @Test
    void badTimestampIs400() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs").header("X-Customer-Id", "acme").param("from", "yesterday"))
                .andExpect(status().isBadRequest());
        verify(repository, never()).find(any(), any(), anyInt());
    }
}
