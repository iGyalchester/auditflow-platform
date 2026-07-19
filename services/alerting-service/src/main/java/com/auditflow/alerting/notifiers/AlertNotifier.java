package com.auditflow.alerting.notifiers;

import com.auditflow.common.model.AlertRule;
import com.auditflow.common.model.AuditEvent;

public interface AlertNotifier {

    void notify(AlertRule rule, AuditEvent event);

    String channel();
}
