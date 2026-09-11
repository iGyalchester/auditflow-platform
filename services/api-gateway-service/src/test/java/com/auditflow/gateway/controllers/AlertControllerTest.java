package com.auditflow.gateway.controllers;

import com.auditflow.gateway.data.AlertHistoryRepository;
import com.auditflow.gateway.data.AlertHistoryRepository.AlertFilter;
import com.auditflow.gateway.data.AlertHistoryRepository.AlertRow;
import com.auditflow.gateway.data.AuditLogRepository;
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

import static org.hamcrest.Matchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AlertController.class)
@Import({SecurityConfig.class, CurrentCustomer.class, RequestScope.class})
class AlertControllerTest {

    private static final AlertRow ALERT = new AlertRow("a-1", "resistance-login-failures", "Failed login attempt", "evt-9",
            Instant.parse("2026-09-02T10:00:05Z"), "slack", "slack,email");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AlertHistoryRepository repository;
    @MockBean
    private AuditLogRepository auditLogs;

    @Test
    void listsTheCustomersAlertsWithRuleNames() throws Exception {
        when(repository.find("resistance", AlertFilter.NONE, 100)).thenReturn(List.of(ALERT));

        mockMvc.perform(get("/api/v1/alerts").header("X-Customer-Id", "resistance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].alertId").value("a-1"))
                .andExpect(jsonPath("$[0].ruleName").value("Failed login attempt"))
                .andExpect(jsonPath("$[0].notifiedChannels").value("slack"))
                .andExpect(jsonPath("$[0].ruleChannels").value("slack,email"));
    }

    @Test
    void feedFiltersReachTheQuery() throws Exception {
        mockMvc.perform(get("/api/v1/alerts").header("X-Customer-Id", "resistance")
                        .param("ruleId", "r-1").param("from", "2026-09-01T00:00:00Z").param("limit", "20"))
                .andExpect(status().isOk());
        verify(repository).find("resistance", new AlertFilter("r-1", Instant.parse("2026-09-01T00:00:00Z"), null), 20);

        mockMvc.perform(get("/api/v1/alerts").header("X-Customer-Id", "resistance")
                        .param("from", "2026-09-02T00:00:00Z").param("to", "2026-09-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void detailShowsTheEventAndWhichConfiguredChannelsWereNotReached() throws Exception {
        when(repository.findOne("resistance", "a-1")).thenReturn(Optional.of(ALERT));
        when(auditLogs.findOne("resistance", "evt-9")).thenReturn(Optional.of(new AuditLogRow("evt-9", "boris", null,
                Instant.parse("2026-09-02T10:00:00Z"), "AUTH_EVENT", "login", "LOGIN_FAILURE", "MEDIUM", false, null)));

        mockMvc.perform(get("/api/v1/alerts/a-1").header("X-Customer-Id", "resistance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alert.alertId").value("a-1"))
                .andExpect(jsonPath("$.event.action").value("LOGIN_FAILURE"))
                .andExpect(jsonPath("$.configuredChannels").value(contains("slack", "email")))
                .andExpect(jsonPath("$.notifiedChannels").value(contains("slack")))
                .andExpect(jsonPath("$.undeliveredChannels").value(contains("email")));
    }

    @Test
    void detailSurvivesAPurgedEventAndIs404ForAnotherTenant() throws Exception {
        when(repository.findOne("resistance", "a-1")).thenReturn(Optional.of(ALERT));
        when(auditLogs.findOne("resistance", "evt-9")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/alerts/a-1").header("X-Customer-Id", "resistance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event").doesNotExist());
        mockMvc.perform(get("/api/v1/alerts/a-1").header("X-Customer-Id", "other-co"))
                .andExpect(status().isNotFound());
    }

    @Test
    void noCustomerIs400() throws Exception {
        mockMvc.perform(get("/api/v1/alerts")).andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
    }
}
