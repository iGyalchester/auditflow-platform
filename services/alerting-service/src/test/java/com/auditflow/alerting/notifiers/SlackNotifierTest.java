package com.auditflow.alerting.notifiers;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.model.AlertRule;
import com.auditflow.common.model.AuditEvent;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SlackNotifierTest {

    private static final String WEBHOOK = "https://hooks.slack.example/services/T000/B000/XXX";

    private final AlertRule rule = AlertRule.builder()
            .ruleId("rule-1")
            .customerId("cust-1")
            .name("High-risk exports")
            .riskThreshold(RiskLevel.HIGH)
            .build();

    private final AuditEvent event = AuditEvent.builder()
            .eventId("evt-1")
            .customerId("cust-1")
            .type(EventType.DATA_EXPORT)
            .resource("customers_table")
            .riskLevel(RiskLevel.HIGH)
            .timestamp(Instant.now())
            .build();

    @Test
    void postsAlertTextToConfiguredWebhook() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SlackNotifier notifier = new SlackNotifier(builder, WEBHOOK);

        server.expect(requestTo(WEBHOOK))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.text", containsString("High-risk exports")))
                .andExpect(jsonPath("$.text", containsString("evt-1")))
                .andRespond(withSuccess());

        notifier.notify(rule, event);

        server.verify();
    }

    @Test
    void fallsBackToLoggingWhenNoWebhookConfigured() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SlackNotifier notifier = new SlackNotifier(builder, "");

        notifier.notify(rule, event);

        server.verify();
    }

    @Test
    void deliveryFailureDoesNotPropagate() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SlackNotifier notifier = new SlackNotifier(builder, WEBHOOK);

        server.expect(requestTo(WEBHOOK)).andRespond(withServerError());

        assertThatCode(() -> notifier.notify(rule, event)).doesNotThrowAnyException();
        server.verify();
    }
}
