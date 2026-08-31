package com.auditflow.alerting.rules;

import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.model.AlertRule;
import com.auditflow.common.model.AuditEvent;
import org.springframework.stereotype.Component;

/**
 * Decides whether an {@link AuditEvent} trips a customer's {@link AlertRule}.
 * All criteria AND together: enabled, same customer, event type (when set),
 * risk at or above the threshold (when set), and the rule's optional
 * conditionExpression - a sandboxed SpEL predicate over the event, see
 * {@link ConditionEvaluator}.
 */
@Component
public class RuleEngine {

    private final ConditionEvaluator conditionEvaluator;

    public RuleEngine(ConditionEvaluator conditionEvaluator) {
        this.conditionEvaluator = conditionEvaluator;
    }

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
        if (rule.getRiskThreshold() != null
                && riskRank(event.getRiskLevel()) < riskRank(rule.getRiskThreshold())) {
            return false;
        }
        return conditionEvaluator.matches(rule.getConditionExpression(), event);
    }

    private int riskRank(RiskLevel riskLevel) {
        return riskLevel == null ? -1 : riskLevel.ordinal();
    }
}
