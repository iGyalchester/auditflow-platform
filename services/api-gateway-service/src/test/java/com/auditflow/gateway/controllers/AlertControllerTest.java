package com.auditflow.gateway.controllers;

import com.auditflow.gateway.data.AlertHistoryRepository;
import com.auditflow.gateway.data.AlertHistoryRepository.AlertRow;
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

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AlertController.class)
@Import({SecurityConfig.class, CurrentCustomer.class, RequestScope.class})
class AlertControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AlertHistoryRepository repository;

    @Test
    void listsTheCustomersAlertsWithRuleNames() throws Exception {
        when(repository.find("resistance", 100)).thenReturn(List.of(
                new AlertRow("a-1", "resistance-login-failures", "Failed login attempt", "evt-9",
                        Instant.parse("2026-09-02T10:00:05Z"), "slack")));

        mockMvc.perform(get("/api/v1/alerts").header("X-Customer-Id", "resistance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].alertId").value("a-1"))
                .andExpect(jsonPath("$[0].ruleName").value("Failed login attempt"))
                .andExpect(jsonPath("$[0].notifiedChannels").value("slack"));
    }

    @Test
    void noCustomerIs400() throws Exception {
        mockMvc.perform(get("/api/v1/alerts")).andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
    }
}
