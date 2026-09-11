package com.auditflow.gateway.controllers;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.model.AlertRule;
import com.auditflow.common.rules.ConditionEvaluator;
import com.auditflow.gateway.data.AlertRuleRepository;
import com.auditflow.gateway.data.AuditLogRepository;
import com.auditflow.gateway.security.CurrentCustomer;
import com.auditflow.gateway.security.SecurityConfig;
import com.auditflow.common.model.AuditEvent;
import com.auditflow.common.enums.RiskLevel;
import java.time.Instant;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Validation only: every case here asserts that a bad request is refused
 * *before* it reaches persistence, so the repository is mocked precisely to
 * assert it was never called. CRUD and tenant scoping live in
 * {@link AlertRuleControllerCrudTest} against a real Postgres, because
 * there the repository's behaviour is the thing under test and a stub would
 * be asserting on itself.
 */
@WebMvcTest(AlertRuleController.class)
@Import({SecurityConfig.class, CurrentCustomer.class, RequestScope.class})
class AlertRuleControllerTest {

    private static final String VALID = """
            {"name":"Failed login","eventType":"AUTH_EVENT",
             "conditionExpression":"action == 'LOGIN_FAILURE'","notificationChannels":["slack"]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AlertRuleRepository repository;
    @MockBean
    private AuditLogRepository auditLogs;

    @Test
    void createsARuleForTheCallingCustomerWithAServerGeneratedId() throws Exception {
        mockMvc.perform(post("/api/v1/alert-rules").header("X-Customer-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/alert-rules/")))
                .andExpect(jsonPath("$.customerId").value("acme"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.ruleId").isNotEmpty());

        ArgumentCaptor<AlertRule> saved = ArgumentCaptor.forClass(AlertRule.class);
        verify(repository).upsert(saved.capture());
        assertThat(saved.getValue().getCustomerId()).isEqualTo("acme");
        assertThat(saved.getValue().getEventType()).isEqualTo(EventType.AUTH_EVENT);
        assertThat(saved.getValue().getNotificationChannels()).containsExactly("slack");
    }

    @Test
    void rejectsAConditionTheSandboxCannotRun() throws Exception {
        String escape = """
                {"name":"x","conditionExpression":"T(java.lang.Runtime).getRuntime() != null"}
                """;
        mockMvc.perform(post("/api/v1/alert-rules").header("X-Customer-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON).content(escape))
                .andExpect(status().isBadRequest());
        String nonBoolean = """
                {"name":"x","conditionExpression":"resource"}
                """;
        mockMvc.perform(post("/api/v1/alert-rules").header("X-Customer-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON).content(nonBoolean))
                .andExpect(status().isBadRequest());
        verify(repository, never()).upsert(any());
    }

    @Test
    void rejectsUnknownChannelsAndBlankNames() throws Exception {
        mockMvc.perform(post("/api/v1/alert-rules").header("X-Customer-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"notificationChannels\":[\"pager\"]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/alert-rules").header("X-Customer-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest());
        verify(repository, never()).upsert(any());
    }

    @Test
    void aRequestWithNoCustomerIs400() throws Exception {
        mockMvc.perform(get("/api/v1/alert-rules")).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAnOversizedCondition() throws Exception {
        String tooLong = "resource == '" + "x".repeat(ConditionEvaluator.MAX_EXPRESSION_LENGTH) + "'";
        String body = "{\"name\":\"x\",\"conditionExpression\":\"" + tooLong + "\"}";

        mockMvc.perform(post("/api/v1/alert-rules").header("X-Customer-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verify(repository, never()).upsert(any());
    }

    @Test
    void rejectsMethodsOutsideTheSandbox() throws Exception {
        // the payload that used to be accepted and then evaluated on an
        // alerting consumer thread, one event at a time
        String body = "{\"name\":\"x\",\"conditionExpression\":\"resource.repeat(200000000) != null\"}";

        mockMvc.perform(post("/api/v1/alert-rules").header("X-Customer-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verify(repository, never()).upsert(any());
    }

    @Test
    void nullChannelIsARejectionNotAServerError() throws Exception {
        mockMvc.perform(post("/api/v1/alert-rules").header("X-Customer-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"notificationChannels\":[\"slack\",null]}"))
                .andExpect(status().isBadRequest());
        verify(repository, never()).upsert(any());
    }

    @Test
    void repeatedChannelsAreStoredOnce() throws Exception {
        mockMvc.perform(post("/api/v1/alert-rules").header("X-Customer-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"notificationChannels\":[\"slack\",\"email\",\"slack\"]}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<AlertRule> saved = ArgumentCaptor.forClass(AlertRule.class);
        verify(repository).upsert(saved.capture());
        assertThat(saved.getValue().getNotificationChannels()).containsExactly("slack", "email");
    }

    @Test
    void validateAnswersInTheBodyNotTheStatus() throws Exception {
        mockMvc.perform(post("/api/v1/alert-rules/validate").header("X-Customer-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conditionExpression\":\"anomalous && resource == 'x'\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.error").doesNotExist());
        mockMvc.perform(post("/api/v1/alert-rules/validate").header("X-Customer-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conditionExpression\":\"T(java.lang.Runtime)\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.error").isString());
        mockMvc.perform(post("/api/v1/alert-rules/validate").header("X-Customer-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
        mockMvc.perform(post("/api/v1/alert-rules/validate")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verify(repository, never()).upsert(any());
    }

    @Test
    void dryRunCountsMatchesWithTheSameSemanticsAsAlerting() throws Exception {
        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        Instant to = Instant.parse("2026-09-08T00:00:00Z");
        when(auditLogs.countEvents("acme", from, to, null, null)).thenReturn(5L);
        // the SQL would already have narrowed to AUTH_EVENT >= MEDIUM; the matcher re-checks either way
        when(auditLogs.findEvents(eq("acme"), eq(from), eq(to), eq(EventType.AUTH_EVENT), eq(RiskLevel.MEDIUM), anyInt()))
                .thenReturn(List.of(
                        event("e-1", EventType.AUTH_EVENT, RiskLevel.MEDIUM, "LOGIN_FAILURE"),
                        event("e-2", EventType.AUTH_EVENT, RiskLevel.LOW, "LOGIN_FAILURE"),
                        event("e-3", EventType.AUTH_EVENT, RiskLevel.HIGH, "LOGIN_SUCCESS"),
                        event("e-4", EventType.DATA_EXPORT, RiskLevel.CRITICAL, "LOGIN_FAILURE"),
                        event("e-5", EventType.AUTH_EVENT, RiskLevel.CRITICAL, "LOGIN_FAILURE")));

        mockMvc.perform(post("/api/v1/alert-rules/dry-run").header("X-Customer-Id", "acme")
                        .param("from", from.toString()).param("to", to.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"AUTH_EVENT\",\"riskThreshold\":\"MEDIUM\","
                                + "\"conditionExpression\":\"action == 'LOGIN_FAILURE'\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanned").value(5))
                .andExpect(jsonPath("$.matched").value(2))
                .andExpect(jsonPath("$.sample.length()").value(2))
                .andExpect(jsonPath("$.sample[0].eventId").value("e-1"))
                .andExpect(jsonPath("$.sample[0].riskLevel").value("MEDIUM"))
                .andExpect(jsonPath("$.sample[1].eventId").value("e-5"))
                .andExpect(jsonPath("$.from").value(from.toString()));
        verify(repository, never()).upsert(any());
    }

    @Test
    void dryRunRefusesAnInvalidConditionAndAnOversizedWindow() throws Exception {
        mockMvc.perform(post("/api/v1/alert-rules/dry-run").header("X-Customer-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conditionExpression\":\"resource.repeat(9)\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
        verify(auditLogs, never()).findEvents(any(), any(), any(), any(), any(), anyInt());

        List<AuditEvent> tooMany = java.util.Collections.nCopies(AlertRuleController.MAX_DRY_RUN_EVENTS + 1,
                event("e", EventType.AUTH_EVENT, RiskLevel.LOW, "x"));
        when(auditLogs.findEvents(eq("acme"), any(), any(), any(), any(), anyInt())).thenReturn(tooMany);
        mockMvc.perform(post("/api/v1/alert-rules/dry-run").header("X-Customer-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"conditionExpression\":\"anomalous\"}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("too_many_events"));
    }

    /** No condition means nothing needs a row in memory: two counts and a five-row sample. */
    @Test
    void dryRunWithoutAConditionIsAnsweredByCounts() throws Exception {
        when(auditLogs.countEvents(eq("acme"), any(), any(), isNull(), isNull())).thenReturn(1284L);
        when(auditLogs.countEvents(eq("acme"), any(), any(), eq(EventType.DATA_EXPORT), eq(RiskLevel.HIGH))).thenReturn(12L);
        when(auditLogs.findEvents(eq("acme"), any(), any(), eq(EventType.DATA_EXPORT), eq(RiskLevel.HIGH), eq(5)))
                .thenReturn(List.of(event("e-9", EventType.DATA_EXPORT, RiskLevel.CRITICAL, "EXPORT")));

        mockMvc.perform(post("/api/v1/alert-rules/dry-run").header("X-Customer-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"DATA_EXPORT\",\"riskThreshold\":\"HIGH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanned").value(1284))
                .andExpect(jsonPath("$.matched").value(12))
                .andExpect(jsonPath("$.sample[0].eventId").value("e-9"));
        verify(auditLogs, never()).findEvents(any(), any(), any(), any(), any(), eq(AlertRuleController.MAX_DRY_RUN_EVENTS + 1));
    }

    /** One dry run at a time per customer; a second one gets a 429 with Retry-After. */
    @Test
    void aSecondConcurrentDryRunForTheSameCustomerIs429() throws Exception {
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(auditLogs.countEvents(eq("acme"), any(), any(), isNull(), isNull())).thenAnswer(inv -> {
            started.countDown();
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return 0L;
        });
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<Integer> first = pool.submit(() -> mockMvc.perform(
                    post("/api/v1/alert-rules/dry-run").header("X-Customer-Id", "acme")
                            .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus());
            assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            mockMvc.perform(post("/api/v1/alert-rules/dry-run").header("X-Customer-Id", "acme")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "2"))
                    .andExpect(jsonPath("$.error").value("rate_limited"));
            // another customer is not held up by acme's run
            mockMvc.perform(post("/api/v1/alert-rules/dry-run").header("X-Customer-Id", "other-co")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk());

            release.countDown();
            assertThat(first.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static AuditEvent event(String id, EventType type, RiskLevel risk, String action) {
        return AuditEvent.builder().eventId(id).customerId("acme").type(type).riskLevel(risk).action(action)
                .resource("login").timestamp(Instant.parse("2026-09-02T00:00:00Z")).build();
    }
}
