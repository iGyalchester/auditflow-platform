package com.auditflow.alerting.rules;

import com.auditflow.common.rules.ConditionEvaluator;
import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.model.AlertRule;
import com.auditflow.common.model.AuditEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineTest {

    private final RuleEngine ruleEngine = new RuleEngine(new ConditionEvaluator());

    @Test
    void matchesWhenRiskMeetsThreshold() {
        AlertRule rule = AlertRule.builder()
                .ruleId("rule-1")
                .customerId("cust-1")
                .eventType(EventType.DATA_EXPORT)
                .riskThreshold(RiskLevel.MEDIUM)
                .enabled(true)
                .build();

        AuditEvent event = auditEvent("cust-1", EventType.DATA_EXPORT, RiskLevel.HIGH);

        assertThat(ruleEngine.matches(rule, event)).isTrue();
    }

    @Test
    void doesNotMatchWhenRiskBelowThreshold() {
        AlertRule rule = AlertRule.builder()
                .ruleId("rule-1")
                .customerId("cust-1")
                .eventType(EventType.DATA_EXPORT)
                .riskThreshold(RiskLevel.CRITICAL)
                .enabled(true)
                .build();

        AuditEvent event = auditEvent("cust-1", EventType.DATA_EXPORT, RiskLevel.HIGH);

        assertThat(ruleEngine.matches(rule, event)).isFalse();
    }

    @Test
    void doesNotMatchDisabledRule() {
        AlertRule rule = AlertRule.builder()
                .ruleId("rule-1")
                .customerId("cust-1")
                .enabled(false)
                .build();

        AuditEvent event = auditEvent("cust-1", EventType.DATA_EXPORT, RiskLevel.CRITICAL);

        assertThat(ruleEngine.matches(rule, event)).isFalse();
    }

    @Test
    void doesNotMatchDifferentCustomer() {
        AlertRule rule = AlertRule.builder()
                .ruleId("rule-1")
                .customerId("cust-1")
                .enabled(true)
                .build();

        AuditEvent event = auditEvent("cust-2", EventType.DATA_EXPORT, RiskLevel.CRITICAL);

        assertThat(ruleEngine.matches(rule, event)).isFalse();
    }

    @Test
    void conditionExpressionNarrowsTheMatch() {
        AlertRule rule = AlertRule.builder()
                .ruleId("rule-1")
                .customerId("cust-1")
                .eventType(EventType.DATA_EXPORT)
                .conditionExpression("resource == 'customers_table'")
                .enabled(true)
                .build();

        AuditEvent hit = auditEvent("cust-1", EventType.DATA_EXPORT, RiskLevel.HIGH)
                .toBuilder().resource("customers_table").build();
        AuditEvent miss = auditEvent("cust-1", EventType.DATA_EXPORT, RiskLevel.HIGH)
                .toBuilder().resource("orders_table").build();

        assertThat(ruleEngine.matches(rule, hit)).isTrue();
        assertThat(ruleEngine.matches(rule, miss)).isFalse();
    }

    @Test
    void ruleWithoutConditionStillMatchesOnTypeAndRiskAlone() {
        AlertRule rule = AlertRule.builder()
                .ruleId("rule-1")
                .customerId("cust-1")
                .eventType(EventType.DATA_EXPORT)
                .riskThreshold(RiskLevel.MEDIUM)
                .enabled(true)
                .build();

        assertThat(ruleEngine.matches(rule,
                auditEvent("cust-1", EventType.DATA_EXPORT, RiskLevel.HIGH))).isTrue();
    }

    private AuditEvent auditEvent(String customerId, EventType type, RiskLevel riskLevel) {
        return AuditEvent.builder()
                .eventId("evt-1")
                .customerId(customerId)
                .type(type)
                .riskLevel(riskLevel)
                .timestamp(Instant.now())
                .build();
    }
}
