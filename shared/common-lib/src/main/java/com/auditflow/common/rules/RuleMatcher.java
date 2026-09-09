package com.auditflow.common.rules;

import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.model.AlertRule;
import com.auditflow.common.model.AuditEvent;

/**
 * Whether an {@link AuditEvent} trips an {@link AlertRule}. All criteria AND
 * together: enabled, same customer, event type (when set), risk at or above
 * the threshold (when set), and the optional condition - a sandboxed SpEL
 * predicate, see {@link ConditionEvaluator}.
 *
 * <p>Shared so that alerting-service (the consumer that raises real alerts)
 * and the gateway's dry run (which answers "how often would this rule have
 * fired last week?" before the rule is saved) cannot drift: one definition
 * of a match, tested once.
 */
public class RuleMatcher {

    private final ConditionEvaluator conditionEvaluator;

    public RuleMatcher(ConditionEvaluator conditionEvaluator) {
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

    private static int riskRank(RiskLevel riskLevel) {
        return riskLevel == null ? -1 : riskLevel.ordinal();
    }
}
