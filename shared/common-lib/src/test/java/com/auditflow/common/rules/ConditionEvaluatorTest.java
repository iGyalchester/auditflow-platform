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
    @Test
    void blocksAllocationAndRegexMethods() {
        // Small arguments on purpose: a regression should fail this test, not
        // exhaust the heap of whoever runs it.
        for (String expression : new String[]{
                "resource.repeat(3) != null",
                "resource.matches('(a+)+$')",
                "resource.replaceAll('a', 'b') != null",
                "resource.getBytes().length > 0",
                "resource.toCharArray().length > 0",
                "resource.chars().count() > 0",
                "resource.hashCode() != 0",
                "resource.getClass() != null",
                "resource.split(',').length > 0",
                "resource.concat('x') != null"}) {
            assertThat(evaluator.matches(expression, export))
                    .as("matches(%s)", expression).isFalse();
            assertThat(evaluator.validate(expression))
                    .as("validate(%s)", expression).contains("not a valid expression");
        }
    }

    @Test
    void blocksMutationOnCollections() {
        assertThat(evaluator.matches("controls.add(null)", export)).isFalse();
        assertThat(evaluator.matches("tags.put('k', 'v') == null", export)).isFalse();
        assertThat(evaluator.matches("tags.clear() == null", export)).isFalse();
    }

    @Test
    void allowsTheDocumentedPredicateMethods() {
        assertThat(evaluator.validate("resource.endsWith('_table')")).isNull();
        assertThat(evaluator.validate("action.equalsIgnoreCase('sample')")).isNull();
        assertThat(evaluator.validate("resource.length() > 3")).isNull();
        assertThat(evaluator.validate("resource.toLowerCase() == 'sample'")).isNull();
        assertThat(evaluator.validate("resource.trim().isEmpty()")).isNull();
        assertThat(evaluator.validate("controls.isEmpty() && controls.size() == 0")).isNull();
        assertThat(evaluator.validate("!tags.containsKey('team')")).isNull();
        assertThat(evaluator.validate("type.name() == 'AUTH_EVENT'")).isNull();

        // and they still evaluate against a real event
        assertThat(evaluator.matches("resource.endsWith('_table')", export)).isTrue();
        assertThat(evaluator.matches("type.name() == 'DATA_EXPORT'", export)).isTrue();
    }

    @Test
    void rejectsExpressionsLongerThanTheCap() {
        String tooLong = "resource == '" + "x".repeat(ConditionEvaluator.MAX_EXPRESSION_LENGTH) + "'";
        assertThat(tooLong.length()).isGreaterThan(ConditionEvaluator.MAX_EXPRESSION_LENGTH);

        assertThat(evaluator.validate(tooLong)).contains("longer than");
        assertThat(evaluator.matches(tooLong, export)).isFalse();

        // one character under the cap is still fine
        String head = "resource == '";
        String padding = "x".repeat(ConditionEvaluator.MAX_EXPRESSION_LENGTH - head.length() - 1);
        assertThat(evaluator.validate(head + padding + "'")).isNull();
    }
}

