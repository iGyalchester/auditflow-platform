package com.auditflow.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The default profile: open, customer from the dev header. */
@SpringBootTest
@AutoConfigureMockMvc
class AuthDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void requestsWithoutTokensAreAccepted() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs")).andExpect(status().isOk());
    }

    @Test
    void customerComesFromTheDevHeader() throws Exception {
        mockMvc.perform(get("/api/v1/me").header(CurrentCustomer.DEV_HEADER, "local-dev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value("local-dev"));
    }
}
