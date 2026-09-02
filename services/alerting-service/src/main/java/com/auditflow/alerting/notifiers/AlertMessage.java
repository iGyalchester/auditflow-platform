package com.auditflow.alerting.notifiers;

import com.auditflow.common.model.AlertRule;
import com.auditflow.common.model.AuditEvent;

/** One wording for every channel, so Slack and email say the same thing. */
public final class AlertMessage {

    private AlertMessage() {
    }

    public static String subject(AlertRule rule, AuditEvent event) {
        return "[AuditFlow] %s - %s risk for %s".formatted(
                rule.getName() != null ? rule.getName() : rule.getRuleId(),
                event.getRiskLevel() != null ? event.getRiskLevel() : "unrated",
                rule.getCustomerId());
    }

    public static String body(AlertRule rule, AuditEvent event) {
        return """
                Rule: %s (%s)
                Customer: %s
                Event: %s %s on %s
                User: %s from %s
                Risk: %s%s
                Event id: %s at %s""".formatted(
                rule.getName() != null ? rule.getName() : rule.getRuleId(), rule.getRuleId(),
                rule.getCustomerId(),
                event.getType(), nullSafe(event.getAction()), nullSafe(event.getResource()),
                nullSafe(event.getUserId()), nullSafe(event.getIpAddress()),
                event.getRiskLevel() != null ? event.getRiskLevel() : "unrated",
                event.isAnomalous() ? " (anomalous)" : "",
                event.getEventId(), event.getTimestamp());
    }

    private static String nullSafe(String value) {
        return value == null ? "-" : value;
    }
}
