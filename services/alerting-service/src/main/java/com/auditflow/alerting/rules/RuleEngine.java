package com.auditflow.alerting.rules;

import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.model.AlertRule;
import com.auditflow.common.model.AuditEvent;
import org.springframework.stereotype.Component;

/**
 * Decides whether an {@link AuditEvent} trips a customer's {@link AlertRule}.
 * The condition_expression field is reserved for a future SpEL/rules-DSL
 * evaluator; today matching is by event type and risk threshold only.
 */
@Component
public class RuleEngine {

    public boolean matches(AlertRule rule, AuditEvent event) {
        if (!rule.isEnabled()) {
            return false;
        }
        if (!rule.getCustomerId().equals(event.getCustomerId())) {
            return false;
        }
        if (rule.getEventType() != null && rule.getEventType() != event.getType()) {
            return false;
        }
        if (rule.getRiskThreshold() != null) {
            return riskRank(event.getRiskLevel()) >= riskRank(rule.getRiskThreshold());
        }
        return true;
    }

    private int riskRank(RiskLevel riskLevel) {
        return riskLevel == null ? -1 : riskLevel.ordinal();
    }
}
