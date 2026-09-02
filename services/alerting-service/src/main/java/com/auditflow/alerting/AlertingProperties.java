package com.auditflow.alerting;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Everything alerting-service needs to know that is not a rule.
 *
 * @param enrichedTopic topic enrichment-service republishes enriched events to
 * @param rulesFile     Spring resource location of the JSON rules array
 *                      ({@code classpath:...} or {@code file:...}); blank = no rules
 * @param slack         incoming-webhook URL; blank = Slack notifier logs only
 * @param email         SES sender/recipients; blank sender = email notifier logs only
 */
@ConfigurationProperties(prefix = "audit.alerting")
public record AlertingProperties(String enrichedTopic, String rulesFile, Slack slack, Email email) {

    public record Slack(String webhookUrl) {
        public boolean configured() {
            return webhookUrl != null && !webhookUrl.isBlank();
        }
    }

    public record Email(String from, List<String> to, String region) {
        public boolean configured() {
            return from != null && !from.isBlank() && to != null && !to.isEmpty();
        }
    }
}
