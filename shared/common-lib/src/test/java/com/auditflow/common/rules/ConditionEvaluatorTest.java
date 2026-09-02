package com.auditflow.common.rules;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.model.AuditEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionEvaluatorTest {

    private final ConditionEvaluator evaluator = new ConditionEvaluator();

    private final AuditEvent export = AuditEvent.builder()
            .eventId("evt-1")
            .customerId("cust-1")
            .type(EventType.DATA_EXPORT)
            .resource("customers_table")
            .ipAddress("203.0.113.7")
            .riskLevel(RiskLevel.HIGH)
            .anomalous(true)
            .timestamp(Instant.now())
            .build();

    @Test
    void nullOrBlankExpressionImposesNoCondition() {
        assertThat(evaluator.matches(null, export)).isTrue();
        assertThat(evaluator.matches("   ", export)).isTrue();
    }

    @Test
    void evaluatesPropertyPredicates() {
        assertThat(evaluator.matches("anomalous && resource == 'customers_table'", export)).isTrue();
        assertThat(evaluator.matches("resource == 'orders_table'", export)).isFalse();
    }

    @Test
    void allowsInstanceMethodsOnTheEvent() {
        assertThat(evaluator.matches("resource.contains('customers')", export)).isTrue();
        assertThat(evaluator.matches("!ipAddress.startsWith('10.')", export)).isTrue();
    }

    @Test
    void blocksTypeReferences() {
        // the classic SpEL-injection payload: SimpleEvaluationContext has no
        // type locator, so this fails to evaluate and matches nothing
        assertThat(evaluator.matches(
                "T(java.lang.Runtime).getRuntime() != null", export)).isFalse();
    }

    @Test
    void blocksConstructorInvocation() {
        assertThat(evaluator.matches(
                "new java.lang.ProcessBuilder('id').start() != null", export)).isFalse();
    }

    @Test
    void unparseableExpressionMatchesNothing() {
        assertThat(evaluator.matches("resource ===== oops((", export)).isFalse();
    }

    @Test
    void nonBooleanResultMatchesNothing() {
        assertThat(evaluator.matches("resource", export)).isFalse();
    }

    @Test
    void validateAcceptsBooleanExpressionsAndBlank() {
        ConditionEvaluator evaluator = new ConditionEvaluator();
        org.assertj.core.api.Assertions.assertThat(evaluator.validate("action == 'LOGIN_FAILURE'")).isNull();
        org.assertj.core.api.Assertions.assertThat(evaluator.validate("anomalous && ipAddress.startsWith('10.')")).isNull();
        org.assertj.core.api.Assertions.assertThat(evaluator.validate(null)).isNull();
        org.assertj.core.api.Assertions.assertThat(evaluator.validate("  ")).isNull();
    }

    @Test
    void validateRejectsSyntaxErrorsNonBooleansAndEscapes() {
        ConditionEvaluator evaluator = new ConditionEvaluator();
        org.assertj.core.api.Assertions.assertThat(evaluator.validate("action == ")).contains("not a valid expression");
        org.assertj.core.api.Assertions.assertThat(evaluator.validate("action")).contains("true/false");
        org.assertj.core.api.Assertions.assertThat(evaluator.validate("T(java.lang.Runtime).getRuntime() != null"))
                .contains("not a valid expression");
        org.assertj.core.api.Assertions.assertThat(evaluator.validate("new java.io.File('/etc') != null"))
                .contains("not a valid expression");
    }
}
