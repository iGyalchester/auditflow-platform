package com.auditflow.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.auditflow.gateway.data.AlertHistoryRepository;
import com.auditflow.gateway.data.AlertRuleRepository;
import com.auditflow.gateway.data.AuditLogRepository;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The filter's behaviour is tested once, in common-lib. What is
 * service-specific is that this service's property prefix reaches it - so
 * that is all this asserts, with a burst of one so the second request is
 * refused.
 *
 * <p>Worth having because the prefix is the one thing the shared filter
 * cannot get right on its own: bind `audit.rate-limit` in ingestion or
 * `audit.ingestion.rate-limit` here and nothing fails, the limiter just
 * silently runs on defaults.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "audit.rate-limit.burst=1",
        "audit.rate-limit.requests-per-second=0.001"})
@AutoConfigureMockMvc
class RateLimitConfigTest {

    @MockBean
    private AuditLogRepository auditLogRepository;
    @MockBean
    private AlertHistoryRepository alertHistoryRepository;
    @MockBean
    private AlertRuleRepository alertRuleRepository;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theGatewayPrefixBindsToTheSharedFilter() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs").header("X-Customer-Id", "acme"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/audit-logs").header("X-Customer-Id", "acme"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }
}
