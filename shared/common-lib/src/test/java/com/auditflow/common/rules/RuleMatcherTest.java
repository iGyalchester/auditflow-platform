package com.auditflow.common.rules;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.model.AlertRule;
import com.auditflow.common.model.AuditEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RuleMatcherTest {

    private final RuleMatcher matcher = new RuleMatcher(new ConditionEvaluator());

    private static AuditEvent event(EventType type, RiskLevel risk, String action) {
        return AuditEvent.builder().eventId("e").customerId("acme").type(type).riskLevel(risk)
                .action(action).resource("login").timestamp(Instant.EPOCH).build();
    }

    private static AlertRule.Builder rule() {
        return AlertRule.builder().ruleId("r").customerId("acme").name("r").enabled(true);
    }

    @Test
    void everyCriterionMustHold() {
        AlertRule rule = rule().eventType(EventType.AUTH_EVENT).riskThreshold(RiskLevel.MEDIUM)
                .conditionExpression("action == 'LOGIN_FAILURE'").build();

        assertThat(matcher.matches(rule, event(EventType.AUTH_EVENT, RiskLevel.HIGH, "LOGIN_FAILURE"))).isTrue();
        assertThat(matcher.matches(rule, event(EventType.AUTH_EVENT, RiskLevel.MEDIUM, "LOGIN_FAILURE"))).isTrue();
        assertThat(matcher.matches(rule, event(EventType.AUTH_EVENT, RiskLevel.LOW, "LOGIN_FAILURE"))).isFalse();
        assertThat(matcher.matches(rule, event(EventType.FILE_ACCESS, RiskLevel.HIGH, "LOGIN_FAILURE"))).isFalse();
        assertThat(matcher.matches(rule, event(EventType.AUTH_EVENT, RiskLevel.HIGH, "LOGIN_SUCCESS"))).isFalse();
    }

    @Test
    void unsetCriteriaImposeNothing() {
        AlertRule rule = rule().build();
        assertThat(matcher.matches(rule, event(EventType.DATA_EXPORT, null, null))).isTrue();
    }

    @Test
    void disabledRulesAndOtherCustomersNeverMatch() {
        assertThat(matcher.matches(rule().enabled(false).build(), event(EventType.AUTH_EVENT, RiskLevel.HIGH, "x")))
                .isFalse();
        assertThat(matcher.matches(rule().customerId("other").build(), event(EventType.AUTH_EVENT, RiskLevel.HIGH, "x")))
                .isFalse();
    }

    @Test
    void anEventWithoutARiskLevelIsBelowEveryThreshold() {
        assertThat(matcher.matches(rule().riskThreshold(RiskLevel.LOW).build(), event(EventType.AUTH_EVENT, null, "x")))
                .isFalse();
    }
}
