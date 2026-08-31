package com.auditflow.alerting.rules;

import com.auditflow.common.model.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evaluates an {@link com.auditflow.common.model.AlertRule}'s
 * conditionExpression against an event, e.g.
 * {@code "anomalous && resource == 'customers_table'"} or
 * {@code "ipAddress != null && !ipAddress.startsWith('10.')"}.
 *
 * <p>Expressions are customer-supplied and therefore untrusted, so they run
 * in Spring's {@link SimpleEvaluationContext} - property reads and instance
 * method calls on the event only. No type references
 * ({@code T(java.lang.Runtime)}), no constructors, no bean references, no
 * assignment: the classic SpEL-injection escape hatches are disabled by
 * construction rather than by blacklist.
 *
 * <p>Fail-closed: an expression that does not parse, throws, or yields a
 * non-boolean matches nothing (logged at WARN so the broken rule is
 * visible), while a null/blank expression imposes no extra condition.
 */
@Component
public class ConditionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ConditionEvaluator.class);
    private static final int MAX_CACHED_EXPRESSIONS = 1_000;

    private final ExpressionParser parser = new SpelExpressionParser();
    private final Map<String, Expression> cache = new ConcurrentHashMap<>();

    public boolean matches(String conditionExpression, AuditEvent event) {
        if (conditionExpression == null || conditionExpression.isBlank()) {
            return true;
        }
        try {
            if (cache.size() > MAX_CACHED_EXPRESSIONS) {
                cache.clear();
            }
            Expression expression = cache.computeIfAbsent(conditionExpression, parser::parseExpression);
            Boolean result = expression.getValue(
                    SimpleEvaluationContext.forReadOnlyDataBinding()
                            .withInstanceMethods()
                            .withRootObject(event)
                            .build(),
                    Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.warn("Alert rule condition '{}' failed to evaluate - treating as no match: {}",
                    conditionExpression, e.getMessage());
            return false;
        }
    }
}
