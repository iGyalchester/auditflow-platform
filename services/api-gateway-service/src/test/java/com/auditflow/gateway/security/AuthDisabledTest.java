package com.auditflow.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.auditflow.gateway.data.AuditLogRepository;
import com.auditflow.gateway.data.AlertHistoryRepository;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The default profile: open, customer from the dev header. */
@SpringBootTest(properties = {"spring.sql.init.mode=never",
        "management.health.db.enabled=false"})
@AutoConfigureMockMvc
class AuthDisabledTest {

    @MockBean
    private AuditLogRepository auditLogRepository;
    @MockBean
    private AlertHistoryRepository alertHistoryRepository;
    @MockBean
    private com.auditflow.gateway.data.AlertRuleRepository alertRuleRepository;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void requestsWithoutTokensAreAccepted() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs").header("X-Customer-Id", "dev")).andExpect(status().isOk());
    }

    @Test
    void customerComesFromTheDevHeader() throws Exception {
        mockMvc.perform(get("/api/v1/me").header(CurrentCustomer.DEV_HEADER, "local-dev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value("local-dev"));
    }

    @Test
    void healthIsUpInOpenMode() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
