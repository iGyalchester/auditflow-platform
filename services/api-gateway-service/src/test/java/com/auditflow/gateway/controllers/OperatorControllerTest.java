package com.auditflow.gateway.controllers;

import com.auditflow.gateway.api.OperatorCustomer;
import com.auditflow.gateway.api.PlatformStats;
import com.auditflow.gateway.api.Stats;
import com.auditflow.gateway.data.OperatorRepository;
import com.auditflow.gateway.security.CurrentCustomer;
import com.auditflow.gateway.security.Roles;
import com.auditflow.gateway.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The role gate is the security chain's; these prove it holds in front of the operator endpoints. */
@WebMvcTest(OperatorController.class)
@Import({SecurityConfig.class, CurrentCustomer.class, RequestScope.class})
class OperatorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OperatorRepository repository;

    @Test
    void operatorsSeeEveryCustomer() throws Exception {
        when(repository.customers(any())).thenReturn(List.of(
                new OperatorCustomer("acme", "Acme Corp", 3, 40, 2, 5, Instant.parse("2026-09-08T10:00:00Z")),
                new OperatorCustomer("resistance", null, 1, 7, 1, 1, Instant.parse("2026-09-08T09:00:00Z"))));

        mockMvc.perform(get("/api/v1/operator/customers").header("X-Customer-Id", "platform")
                        .header(Roles.DEV_ROLES_HEADER, "operator"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].customerId").value("acme"))
                .andExpect(jsonPath("$[0].events7d").value(40))
                .andExpect(jsonPath("$[1].name").doesNotExist());
    }

    @Test
    void platformStatsHonourTheWindow() throws Exception {
        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        Instant to = Instant.parse("2026-09-02T00:00:00Z");
        when(repository.stats(from, to)).thenReturn(new PlatformStats(new Stats.Window(from, to),
                new PlatformStats.Totals(5, 1, 2), List.of(), List.of(), Map.of()));

        mockMvc.perform(get("/api/v1/operator/stats").header("X-Customer-Id", "platform")
                        .header(Roles.DEV_ROLES_HEADER, "operator")
                        .param("from", from.toString()).param("to", to.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.customers").value(2));
    }

    /** The gate travels with the code, not only with the URL it happens to be mounted on. */
    @Test
    void theControllerCarriesItsOwnRoleGate() {
        org.springframework.security.access.prepost.PreAuthorize gate =
                OperatorController.class.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
        org.assertj.core.api.Assertions.assertThat(gate).isNotNull();
        org.assertj.core.api.Assertions.assertThat(gate.value()).isEqualTo("hasRole('OPERATOR')");
    }

    @Test
    void aPlainUserIs403AndAnonymousIs401() throws Exception {
        mockMvc.perform(get("/api/v1/operator/customers").header("X-Customer-Id", "acme"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mockMvc.perform(get("/api/v1/operator/stats").header("X-Customer-Id", "acme"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/operator/customers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthenticated"));
        verifyNoInteractions(repository);
    }
}
