package com.auditflow.alerting.notifiers;

import com.auditflow.alerting.AlertingProperties;
import com.auditflow.common.model.AlertRule;
import com.auditflow.common.model.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * Posts alerts to a Slack incoming webhook. With no webhook configured it
 * logs the alert instead - the dev default - so the service runs without
 * a Slack workspace and the log line shows what would have been sent.
 */
@Component
public class SlackNotifier implements AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(SlackNotifier.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;
    private final String webhookUrl;

    public SlackNotifier(AlertingProperties props) {
        this.webhookUrl = props.slack().configured() ? props.slack().webhookUrl() : null;
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(TIMEOUT);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public void notify(AlertRule rule, AuditEvent event) {
        String text = "*" + AlertMessage.subject(rule, event) + "*\n```" + AlertMessage.body(rule, event) + "```";
        if (webhookUrl == null) {
            log.info("[slack:not configured] {}", AlertMessage.subject(rule, event));
            return;
        }
        restClient.post()
                .uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("text", text))
                .retrieve()
                .toBodilessEntity();
        log.info("[slack] sent rule={} event={}", rule.getRuleId(), event.getEventId());
    }

    @Override
    public String channel() {
        return "slack";
    }
}
