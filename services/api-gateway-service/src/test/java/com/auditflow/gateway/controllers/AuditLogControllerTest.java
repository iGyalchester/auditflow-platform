package com.auditflow.gateway.controllers;

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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuditLogRepository repository;

    @Test
    void listsTheHeaderCustomersEventsWithFilters() throws Exception {
        when(repository.find(eq("acme"), eq("AUTH_EVENT"), eq(Instant.parse("2026-09-01T00:00:00Z")), isNull(), eq(50)))
                .thenReturn(List.of(new AuditLogRow("evt-1", "boris", null, Instant.parse("2026-09-02T10:00:00Z"),
                        "AUTH_EVENT", "login", "LOGIN_FAILURE", "MEDIUM", false, "SOC2:AC-2,SOC2:IA-2")));

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
    void defaultsToOneHundredRowsAndNoFilters() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs").header("X-Customer-Id", "acme"))
                .andExpect(status().isOk());

        verify(repository).find("acme", null, null, null, 100);
    }

    @Test
    void noCustomerIs400AndNeverQueries() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs")).andExpect(status().isBadRequest());
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
        verify(repository, org.mockito.Mockito.never()).find(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }
}
