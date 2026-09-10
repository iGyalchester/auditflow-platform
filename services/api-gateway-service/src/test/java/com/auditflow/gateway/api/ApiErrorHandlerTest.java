package com.auditflow.gateway.api;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.model.AuditEvent;
import com.auditflow.gateway.controllers.AlertRuleController;
import com.auditflow.gateway.controllers.ReportController;
import com.auditflow.gateway.controllers.ReportsConfig;
import com.auditflow.gateway.controllers.RequestScope;
import com.auditflow.gateway.data.AlertRuleRepository;
import com.auditflow.gateway.data.AuditLogRepository;
import com.auditflow.gateway.security.CurrentCustomer;
import com.auditflow.gateway.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every failure the real controllers raise comes back in the one shape
 * the console parses: {@code {"error", "message", "fields"?}}.
 */
@WebMvcTest({AlertRuleController.class, ReportController.class})
@Import({SecurityConfig.class, CurrentCustomer.class, RequestScope.class, ReportsConfig.class})
class ApiErrorHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AlertRuleRepository rules;
    @MockBean
    private AuditLogRepository auditLogs;

    @Test
    void beanValidationNamesTheFields() throws Exception {
        mockMvc.perform(post("/api/v1/alert-rules").header("X-Customer-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("validation"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.fields.name").isString());
    }

    @Test
    void aRejectedConditionCarriesTheEvaluatorsReason() throws Exception {
        mockMvc.perform(post("/api/v1/alert-rules").header("X-Customer-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"conditionExpression\":\"T(java.lang.Runtime)\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.fields").doesNotExist());
    }

    @Test
    void unreadableJsonIsABadRequestNotA500() throws Exception {
        mockMvc.perform(post("/api/v1/alert-rules").header("X-Customer-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(containsString("could not be read")));
    }

    @Test
    void aBadTimestampSaysWhichParameterAndWhatItExpected() throws Exception {
        mockMvc.perform(get("/api/v1/reports/soc2").header("X-Customer-Id", "acme").param("from", "yesterday"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(containsString("'from'")))
                .andExpect(jsonPath("$.message").value(containsString("ISO-8601")));
    }

    @Test
    void notFoundAndForbiddenHaveCodes() throws Exception {
        mockMvc.perform(delete("/api/v1/alert-rules/nope").header("X-Customer-Id", "acme"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"))
                .andExpect(jsonPath("$.message").value("no such rule"));
        mockMvc.perform(get("/api/v1/alert-rules").header("X-Customer-Id", "acme")
                        .header(RequestScope.ACTING_HEADER, "other"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void tooManyEventsIs413WithItsOwnCode() throws Exception {
        AuditEvent event = AuditEvent.builder().eventId("evt").customerId("acme")
                .type(EventType.AUTH_EVENT).timestamp(Instant.now()).build();
        when(auditLogs.findForReport(any(), any(), any(), any(), anyInt()))
                .thenReturn(Collections.nCopies(10_001, event));
        mockMvc.perform(get("/api/v1/reports/soc2").header("X-Customer-Id", "acme"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("too_many_events"))
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    void anUnexpectedFailureIsAnOpaque500() throws Exception {
        when(rules.findAll(any())).thenThrow(new IllegalStateException("connection refused to db-host:5432"));
        mockMvc.perform(get("/api/v1/alert-rules").header("X-Customer-Id", "acme"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("internal"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(containsString("db-host"))));
    }

    /** Spring's own request-level refusals keep their status instead of becoming 500s. */
    @Test
    void springsOwnRefusalsKeepTheirStatus() throws Exception {
        mockMvc.perform(post("/api/v1/alert-rules").header("X-Customer-Id", "acme")
                        .contentType(MediaType.TEXT_PLAIN).content("name=x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("unsupported_media_type"))
                .andExpect(jsonPath("$.message").value(containsString("text/plain")));
        // a 406 keeps its status; the body stays empty because no JSON can be
        // written to a client that only accepts image/png
        mockMvc.perform(get("/api/v1/alert-rules").header("X-Customer-Id", "acme").accept(MediaType.IMAGE_PNG))
                .andExpect(status().isNotAcceptable());
    }

    @Test
    void wrongMethodIs405() throws Exception {
        mockMvc.perform(post("/api/v1/reports/soc2").header("X-Customer-Id", "acme"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("method_not_allowed"));
    }

    @Test
    void codesForStatusesWithoutASpecialName() {
        assertThat(ApiErrorHandler.code(HttpStatus.CONFLICT)).isEqualTo("conflict");
        assertThat(ApiErrorHandler.code(HttpStatus.SERVICE_UNAVAILABLE)).isEqualTo("service_unavailable");
        assertThat(ApiErrorHandler.code(HttpStatus.PAYLOAD_TOO_LARGE)).isEqualTo("too_many_events");
    }
}
