package com.auditflow.gateway.controllers;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.model.AlertRule;
import com.auditflow.common.rules.ConditionEvaluator;
import com.auditflow.gateway.data.AlertRuleRepository;
import com.auditflow.gateway.security.CurrentCustomer;
import com.auditflow.gateway.security.SecurityConfig;
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
    void anotherCustomersRuleIsNotFoundForReplaceAndDelete() throws Exception {
        when(repository.find("acme", "r-other")).thenReturn(Optional.empty());
        when(repository.delete("acme", "r-other")).thenReturn(false);

        mockMvc.perform(put("/api/v1/alert-rules/r-other").header("X-Customer-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/alert-rules/r-other").header("X-Customer-Id", "acme"))
                .andExpect(status().isNotFound());
        verify(repository, never()).upsert(any());
    }

    @Test
    void replaceKeepsTheIdAndCustomer() throws Exception {
        when(repository.find("acme", "r1")).thenReturn(Optional.of(
                AlertRule.builder().ruleId("r1").customerId("acme").name("old").build()));

        mockMvc.perform(put("/api/v1/alert-rules/r1").header("X-Customer-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleId").value("r1"))
                .andExpect(jsonPath("$.name").value("Failed login"));
    }

    @Test
    void listAndGetAreScopedAndDeleteIs204() throws Exception {
        when(repository.findAll("acme")).thenReturn(List.of(AlertRule.builder().ruleId("r1").customerId("acme").name("A").build()));
        when(repository.find("acme", "r1")).thenReturn(Optional.of(AlertRule.builder().ruleId("r1").customerId("acme").name("A").build()));
        when(repository.delete("acme", "r1")).thenReturn(true);

        mockMvc.perform(get("/api/v1/alert-rules").header("X-Customer-Id", "acme"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].ruleId").value("r1"));
        mockMvc.perform(get("/api/v1/alert-rules/r1").header("X-Customer-Id", "acme"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("A"));
        mockMvc.perform(delete("/api/v1/alert-rules/r1").header("X-Customer-Id", "acme"))
                .andExpect(status().isNoContent());
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
}

