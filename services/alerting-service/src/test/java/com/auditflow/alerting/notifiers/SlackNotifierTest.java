package com.auditflow.alerting.notifiers;

import com.auditflow.alerting.AlertingProperties;
import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.model.AlertRule;
import com.auditflow.common.model.AuditEvent;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SlackNotifierTest {

    private HttpServer server;
    private final CompletableFuture<String> received = new CompletableFuture<>();

    private final AlertRule rule = AlertRule.builder().ruleId("r1").customerId("acme").name("Failed login")
            .notificationChannels(List.of("slack")).build();
    private final AuditEvent event = AuditEvent.builder().eventId("evt-7").customerId("acme")
            .type(EventType.AUTH_EVENT).action("LOGIN_FAILURE").userId("boris").ipAddress("203.0.113.7")
            .riskLevel(RiskLevel.HIGH).timestamp(Instant.parse("2026-09-02T10:00:00Z")).build();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                received.complete(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private SlackNotifier notifier(String url) {
        return new SlackNotifier(new AlertingProperties("t", "", new AlertingProperties.Slack(url),
                new AlertingProperties.Email("", List.of(), "")));
    }

    @Test
    void postsTheAlertAsJsonTextToTheWebhook() throws Exception {
        notifier("http://127.0.0.1:" + server.getAddress().getPort() + "/hook").notify(rule, event);

        String body = received.get(5, TimeUnit.SECONDS);
        assertThat(body).startsWith("{\"text\":\"")
                .contains("[AuditFlow] Failed login - HIGH risk for acme")
                .contains("LOGIN_FAILURE").contains("boris").contains("203.0.113.7").contains("evt-7");
    }

    @Test
    void withoutAWebhookItOnlyLogs() {
        assertThatCode(() -> notifier("").notify(rule, event)).doesNotThrowAnyException();
        assertThat(received).isNotDone();
    }
}
