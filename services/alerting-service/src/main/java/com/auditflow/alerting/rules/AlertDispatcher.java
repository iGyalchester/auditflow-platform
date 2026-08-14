package com.auditflow.alerting.rules;

import com.auditflow.alerting.adapters.AlertHistoryRepository;
import com.auditflow.alerting.adapters.AlertRuleRepository;
import com.auditflow.alerting.notifiers.AlertNotifier;
import com.auditflow.common.model.AlertRule;
import com.auditflow.common.model.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Evaluates an enriched event against the owning customer's alert rules and,
 * for every match, notifies the rule's channels and records the alert. A
 * rule with no notification_channels configured goes to every notifier.
 */
@Component
public class AlertDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatcher.class);

    private final RuleEngine ruleEngine;
    private final AlertRuleRepository ruleRepository;
    private final AlertHistoryRepository historyRepository;
    private final List<AlertNotifier> notifiers;

    public AlertDispatcher(RuleEngine ruleEngine,
                           AlertRuleRepository ruleRepository,
                           AlertHistoryRepository historyRepository,
                           List<AlertNotifier> notifiers) {
        this.ruleEngine = ruleEngine;
        this.ruleRepository = ruleRepository;
        this.historyRepository = historyRepository;
        this.notifiers = notifiers;
    }

    public void dispatch(AuditEvent event) {
        List<AlertRule> rules = ruleRepository.findEnabledByCustomer(event.getCustomerId());
        for (AlertRule rule : rules) {
            if (!ruleEngine.matches(rule, event)) {
                continue;
            }
            List<AlertNotifier> targets = notifiers.stream()
                    .filter(n -> rule.getNotificationChannels().isEmpty()
                            || rule.getNotificationChannels().contains(n.channel()))
                    .toList();
            for (AlertNotifier notifier : targets) {
                notifier.notify(rule, event);
            }
            historyRepository.record(rule, event, targets.stream().map(AlertNotifier::channel).toList());
            log.info("Alert triggered: rule={} customer={} event={} channels={}",
                    rule.getRuleId(), event.getCustomerId(), event.getEventId(),
                    targets.stream().map(AlertNotifier::channel).toList());
        }
    }
}
