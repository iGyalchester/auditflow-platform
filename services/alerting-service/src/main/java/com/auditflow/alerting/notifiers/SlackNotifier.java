package com.auditflow.alerting.notifiers;

import com.auditflow.common.model.AlertRule;
import com.auditflow.common.model.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * Posts alerts to a Slack incoming webhook. Falls back to logging when no
 * webhook URL is configured (local/dev/CI), so the service never requires a
 * secret just to boot. Delivery failures are logged, not propagated - one
 * dead channel must not stop the other notifiers or the alert pipeline.
 */
@Component
public class SlackNotifier implements AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(SlackNotifier.class);

    private final RestClient restClient;
    private final String webhookUrl;

    public SlackNotifier(RestClient.Builder restClientBuilder,
                         @Value("${alerting.slack.webhook-url:}") String webhookUrl) {
        this.restClient = restClientBuilder.build();
        this.webhookUrl = webhookUrl;
    }

    @Override
    public void notify(AlertRule rule, AuditEvent event) {
        String message = "AuditFlow alert: rule '%s' (%s) matched event %s [type=%s, risk=%s, resource=%s]"
                .formatted(rule.getName(), rule.getRuleId(), event.getEventId(),
                        event.getType(), event.getRiskLevel(), event.getResource());
        if (webhookUrl.isBlank()) {
            log.info("[slack:fallback] no webhook configured - {}", message);
            return;
        }
        try {
            restClient.post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("text", message))
                    .retrieve()
                    .toBodilessEntity();
            log.debug("[slack] delivered alert for rule={} event={}", rule.getRuleId(), event.getEventId());
        } catch (RestClientException e) {
            log.error("[slack] failed to deliver alert for rule={} event={}",
                    rule.getRuleId(), event.getEventId(), e);
        }
    }

    @Override
    public String channel() {
        return "slack";
    }
}
