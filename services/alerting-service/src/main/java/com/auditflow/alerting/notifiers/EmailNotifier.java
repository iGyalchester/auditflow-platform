package com.auditflow.alerting.notifiers;

import com.auditflow.common.model.AlertRule;
import com.auditflow.common.model.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stub notifier - logs the alert instead of sending real email until an
 * SMTP/SES integration is implemented.
 */
@Component
public class EmailNotifier implements AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(EmailNotifier.class);

    @Override
    public void notify(AlertRule rule, AuditEvent event) {
        log.info("[email] rule={} customer={} event={} risk={}",
                rule.getRuleId(), rule.getCustomerId(), event.getEventId(), event.getRiskLevel());
    }

    @Override
    public String channel() {
        return "email";
    }
}
