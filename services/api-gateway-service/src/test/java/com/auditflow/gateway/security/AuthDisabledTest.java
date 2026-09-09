package com.auditflow.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.auditflow.gateway.data.AuditLogRepository;
import com.auditflow.gateway.data.AlertHistoryRepository;
import org.springframework.test.web.servlet.MockMvc;
import com.auditflow.gateway.controllers.RequestScope;

import java.util.Optional;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The default profile: open, customer from the dev header. */
@SpringBootTest(properties = {"spring.flyway.enabled=false",
        "management.health.db.enabled=false"})
@AutoConfigureMockMvc
class AuthDisabledTest {

    @MockBean
    private AuditLogRepository auditLogRepository;
    @MockBean
    private AlertHistoryRepository alertHistoryRepository;
    @MockBean
    private com.auditflow.gateway.data.AlertRuleRepository alertRuleRepository;
    @MockBean
    private com.auditflow.gateway.data.CustomerRepository customerRepository;
    @MockBean
    private com.auditflow.gateway.data.OperatorRepository operatorRepository;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void requestsWithoutTokensAreAccepted() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs").header("X-Customer-Id", "dev")).andExpect(status().isOk());
    }

    @Test
    void customerComesFromTheDevHeader() throws Exception {
        when(customerRepository.findName("local-dev")).thenReturn(Optional.of("Local Dev Co"));
        mockMvc.perform(get("/api/v1/me").header(CurrentCustomer.DEV_HEADER, "local-dev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value("local-dev"))
                .andExpect(jsonPath("$.customerName").value("Local Dev Co"))
                .andExpect(jsonPath("$.roles").value(contains("USER")))
                .andExpect(jsonPath("$.subject").doesNotExist());
    }

    @Test
    void noCustomerHeaderIsA400WithTheSharedErrorShape() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(containsString("X-Customer-Id")));
    }

    @Test
    void theDevRolesHeaderGrantsTheOperatorRole() throws Exception {
        mockMvc.perform(get("/api/v1/me").header(CurrentCustomer.DEV_HEADER, "platform")
                        .header(Roles.DEV_ROLES_HEADER, "Operator"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").value(contains("USER", "OPERATOR")));
        mockMvc.perform(get("/api/v1/operator/customers").header(CurrentCustomer.DEV_HEADER, "platform")
                        .header(Roles.DEV_ROLES_HEADER, "operator"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/operator/customers").header(CurrentCustomer.DEV_HEADER, "platform"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mockMvc.perform(get("/api/v1/operator/customers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthenticated"));
    }

    @Test
    void anOperatorActsAsAnotherCustomerThroughTheActingHeader() throws Exception {
        when(customerRepository.findName("acme")).thenReturn(Optional.of("Acme Corp"));
        mockMvc.perform(get("/api/v1/me").header(CurrentCustomer.DEV_HEADER, "platform")
                        .header(Roles.DEV_ROLES_HEADER, "operator")
                        .header(RequestScope.ACTING_HEADER, "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value("platform"))
                .andExpect(jsonPath("$.actingAs").value("acme"))
                .andExpect(jsonPath("$.actingAsName").value("Acme Corp"));
        // the queries run as the acting customer
        mockMvc.perform(get("/api/v1/audit-logs").header(CurrentCustomer.DEV_HEADER, "platform")
                        .header(Roles.DEV_ROLES_HEADER, "operator")
                        .header(RequestScope.ACTING_HEADER, "acme"))
                .andExpect(status().isOk());
        verify(auditLogRepository).find("acme", AuditLogRepository.AuditLogFilter.NONE, 100);

        mockMvc.perform(get("/api/v1/audit-logs").header(CurrentCustomer.DEV_HEADER, "other-co")
                        .header(RequestScope.ACTING_HEADER, "acme"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void consoleIsServedAndConfigSaysAuthIsOff() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk());
        mockMvc.perform(get("/rules/r-1")).andExpect(status().isOk())
                .andExpect(content().string(containsString("test shell")));
        mockMvc.perform(get("/config.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authEnabled").value(false))
                .andExpect(jsonPath("$.issuerUri").doesNotExist());
        mockMvc.perform(get("/assets/nope.css")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/nothing").header(CurrentCustomer.DEV_HEADER, "acme"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/anything-else")).andExpect(status().isUnauthorized());
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
