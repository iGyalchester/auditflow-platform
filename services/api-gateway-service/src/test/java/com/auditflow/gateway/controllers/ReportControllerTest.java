package com.auditflow.gateway.controllers;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.model.AuditEvent;
import com.auditflow.common.model.ComplianceControl;
import com.auditflow.gateway.data.AuditLogRepository;
import com.auditflow.gateway.security.CurrentCustomer;
import com.auditflow.gateway.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReportController.class)
@Import({SecurityConfig.class, CurrentCustomer.class, RequestScope.class, ReportsConfig.class})
class ReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuditLogRepository repository;

    private static AuditEvent event(String id, String framework, String control) {
        return AuditEvent.builder().eventId(id).customerId("acme").type(EventType.AUTH_EVENT)
                .riskLevel(RiskLevel.MEDIUM).timestamp(Instant.parse("2026-08-15T00:00:00Z"))
                .controls(List.of(ComplianceControl.builder().framework(framework).controlId(control).build()))
                .build();
    }

    @Test
    void generatesAFrameworkReportOverTheCustomersEventsInTheWindow() throws Exception {
        when(repository.findForReport(eq("acme"), eq(Instant.parse("2026-08-01T00:00:00Z")),
                eq(Instant.parse("2026-09-01T00:00:00Z")), anyInt()))
                .thenReturn(List.of(event("evt-soc2", "SOC2", "AC-2"), event("evt-gdpr", "GDPR", "Art-30")));

        mockMvc.perform(get("/api/v1/reports/SOC2").header("X-Customer-Id", "acme")
                        .param("from", "2026-08-01T00:00:00Z").param("to", "2026-09-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"soc2-acme-2026-08-01.txt\""))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("SOC 2 Evidence Report")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Customer: acme")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("evt-soc2")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("evt-gdpr"))));
    }

    @Test
    void listsFrameworksAndRejectsUnknownOnes() throws Exception {
        mockMvc.perform(get("/api/v1/reports").header("X-Customer-Id", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.contains("gdpr", "hipaa", "soc2")));
        mockMvc.perform(get("/api/v1/reports/pci").header("X-Customer-Id", "acme"))
                .andExpect(status().isNotFound());
    }

    @Test
    void invertedWindowIs400AndNoCustomerIs400() throws Exception {
        mockMvc.perform(get("/api/v1/reports/soc2").header("X-Customer-Id", "acme")
                        .param("from", "2026-09-01T00:00:00Z").param("to", "2026-08-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/reports/soc2")).andExpect(status().isBadRequest());
    }

    @Test
    void aWindowOverTheCapIs413NotATruncatedReport() throws Exception {
        when(repository.findForReport(eq("acme"), any(), any(), anyInt()))
                .thenReturn(Collections.nCopies(ReportController.MAX_EVENTS + 1, event("e", "SOC2", "AC-2")));

        mockMvc.perform(get("/api/v1/reports/soc2").header("X-Customer-Id", "acme"))
                .andExpect(status().isPayloadTooLarge());
    }
}
