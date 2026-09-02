package com.auditflow.alerting.dispatch;

import com.auditflow.alerting.notifiers.AlertNotifier;
import com.auditflow.alerting.rules.RuleEngine;
import com.auditflow.alerting.rules.RuleRepository;
import com.auditflow.common.model.AlertRule;
import com.auditflow.common.model.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * For one enriched event: every rule of that customer that matches fires
 * on every channel it names. One channel failing (Slack down) never stops
 * the others (email) - the failure is logged with the rule and event ids
 * so it can be chased, and the event is not re-queued, because a retry
 * would re-page the channels that already succeeded.
 */
@Component
public class AlertDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatcher.class);

    private final RuleRepository rules;
    private final RuleEngine engine;
    private final Map<String, AlertNotifier> notifiersByChannel;

    public AlertDispatcher(RuleRepository rules, RuleEngine engine, List<AlertNotifier> notifiers) {
        this.rules = rules;
        this.engine = engine;
        this.notifiersByChannel = notifiers.stream()
                .collect(Collectors.toMap(AlertNotifier::channel, Function.identity()));
    }

    /** @return how many (rule, channel) notifications were attempted */
    public int dispatch(AuditEvent event) {
        int fired = 0;
        for (AlertRule rule : rules.rulesFor(event.getCustomerId())) {
            if (!engine.matches(rule, event)) {
                continue;
            }
            for (String channel : rule.getNotificationChannels()) {
                AlertNotifier notifier = notifiersByChannel.get(channel);
                if (notifier == null) {
                    log.warn("Rule {} names unknown channel '{}' (known: {})",
                            rule.getRuleId(), channel, notifiersByChannel.keySet());
                    continue;
                }
                fired++;
                try {
                    notifier.notify(rule, event);
                } catch (Exception e) {
                    log.error("Notifier '{}' failed for rule {} event {}: {}",
                            channel, rule.getRuleId(), event.getEventId(), e.toString());
                }
            }
        }
        return fired;
    }
}
