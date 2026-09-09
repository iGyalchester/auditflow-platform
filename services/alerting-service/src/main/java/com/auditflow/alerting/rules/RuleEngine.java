package com.auditflow.alerting.rules;

import com.auditflow.common.model.AlertRule;
import com.auditflow.common.model.AuditEvent;
import com.auditflow.common.rules.ConditionEvaluator;
import com.auditflow.common.rules.RuleMatcher;
import org.springframework.stereotype.Component;

/**
 * Decides whether an {@link AuditEvent} trips a customer's {@link AlertRule}.
 * All criteria AND together: enabled, same customer, event type (when set),
 * risk at or above the threshold (when set), and the rule's optional
 * conditionExpression - a sandboxed SpEL predicate over the event, see
 * {@link ConditionEvaluator} (shared in common-lib).
 *
 * <p>The decision itself lives in common-lib's {@link RuleMatcher}, which
 * the gateway's rule dry run shares, so "would this fire?" is answered the
 * same way before a rule is saved as after.
 */
@Component
public class RuleEngine {

    private final RuleMatcher matcher;

    public RuleEngine(ConditionEvaluator conditionEvaluator) {
        this.matcher = new RuleMatcher(conditionEvaluator);
    }

    public boolean matches(AlertRule rule, AuditEvent event) {
        return matcher.matches(rule, event);
    }
}
