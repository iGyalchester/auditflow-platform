package com.auditflow.alerting.notifiers;

import com.auditflow.common.model.AlertRule;
import com.auditflow.common.model.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stub notifier - logs the alert instead of calling a real Slack webhook
 * until customer-configurable webhook URLs are implemented.
 */
@Component
public class SlackNotifier implements AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(SlackNotifier.class);

    @Override
    public void notify(AlertRule rule, AuditEvent event) {
        log.info("[slack] rule={} customer={} event={} risk={}",
                rule.getRuleId(), rule.getCustomerId(), event.getEventId(), event.getRiskLevel());
    }

    @Override
    public String channel() {
        return "slack";
    }
}
