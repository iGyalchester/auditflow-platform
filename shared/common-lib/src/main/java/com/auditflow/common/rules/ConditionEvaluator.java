package com.auditflow.common.rules;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.model.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.MethodResolver;
import org.springframework.expression.spel.SpelParserConfiguration;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evaluates an {@link com.auditflow.common.model.AlertRule}'s
 * conditionExpression against an event, e.g.
 * {@code "anomalous && resource == 'customers_table'"} or
 * {@code "ipAddress != null && !ipAddress.startsWith('10.')"}.
 *
 * <p>Expressions are customer-supplied and therefore untrusted, so they run
 * in Spring's {@link SimpleEvaluationContext}: property reads plus the small
 * set of methods {@link AllowListMethodResolver} names. No type references
 * ({@code T(java.lang.Runtime)}), no constructors, no bean references, no
 * assignment - the classic SpEL-injection escape hatches are disabled by
 * construction rather than by blacklist.
 *
 * <p>Two limits keep a syntactically legal expression from costing more than
 * it should. The allow-list is the important one: without it any public
 * instance method is callable, and {@code resource.repeat(200000000)} or
 * {@code resource.matches('(a+)+$')} turn one rule into an out-of-memory or
 * a CPU burn on a consumer thread shared by every tenant. The length cap
 * ({@value #MAX_EXPRESSION_LENGTH} characters) bounds parse cost and is
 * mirrored by a {@code @Size} on the API's request record.
 *
 * <p>Fail-closed: an expression that does not parse, throws, or yields a
 * non-boolean matches nothing (logged at WARN so the broken rule is
 * visible), while a null/blank expression imposes no extra condition.
 *
 * <p>{@link #validate(String)} runs the same sandbox against a fully
 * populated sample event so a rule can be rejected at write time - a
 * syntax error, a non-boolean result, or an escape attempt such as
 * {@code T(java.lang.Runtime)} all fail there instead of silently
 * matching nothing in production.
 */
public class ConditionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ConditionEvaluator.class);
    private static final int MAX_CACHED_EXPRESSIONS = 1_000;

    /** Long enough for any predicate over an event; short enough to be cheap to parse. */
    public static final int MAX_EXPRESSION_LENGTH = 512;

    private static final MethodResolver METHOD_RESOLVER = new AllowListMethodResolver();

    /** Every field populated, so validation never trips over a null it would not see in production. */
    static final AuditEvent SAMPLE_EVENT = AuditEvent.builder()
            .eventId("sample").customerId("sample").userId("sample-user").sessionId("sample-session")
            .timestamp(Instant.EPOCH).type(EventType.AUTH_EVENT).resource("sample").action("SAMPLE")
            .query("").ipAddress("203.0.113.1").userAgent("sample").location("sample")
            .controls(List.of()).riskLevel(RiskLevel.LOW).anomalous(false).tags(Map.of()).rawLog("")
            .build();

    private final ExpressionParser parser = new SpelExpressionParser(
            new SpelParserConfiguration(null, null, false, false, Integer.MAX_VALUE, MAX_EXPRESSION_LENGTH));
    private final Map<String, Expression> cache = new ConcurrentHashMap<>();

    /**
     * Read-only data binding plus the allow-listed methods. Note the absence
     * of {@code withInstanceMethods()}: it and {@code withMethodResolvers}
     * are alternatives, and calling both would put every public method back.
     */
    private static EvaluationContext context(Object root) {
        return SimpleEvaluationContext.forReadOnlyDataBinding()
                .withMethodResolvers(METHOD_RESOLVER)
                .withRootObject(root)
                .build();
    }

    /**
     * @return null when the expression is acceptable, otherwise a message
     *         suitable for a 400 response
     */
    public String validate(String conditionExpression) {
        if (conditionExpression == null || conditionExpression.isBlank()) {
            return null;
        }
        if (conditionExpression.length() > MAX_EXPRESSION_LENGTH) {
            // said plainly here rather than as the parser's own error, which
            // is what the caller would otherwise see in a 400
            return "condition is longer than " + MAX_EXPRESSION_LENGTH + " characters";
        }
        try {
            Expression expression = parser.parseExpression(conditionExpression);
            Object result = expression.getValue(context(SAMPLE_EVENT));
            if (!(result instanceof Boolean)) {
                return "condition must evaluate to true/false, got "
                        + (result == null ? "null" : result.getClass().getSimpleName());
            }
            return null;
        } catch (Exception e) {
            return "condition is not a valid expression over an event: " + e.getMessage();
        }
    }

    public boolean matches(String conditionExpression, AuditEvent event) {
        if (conditionExpression == null || conditionExpression.isBlank()) {
            return true;
        }
        try {
            if (cache.size() > MAX_CACHED_EXPRESSIONS) {
                cache.clear();
            }
            Expression expression = cache.computeIfAbsent(conditionExpression, parser::parseExpression);
            Boolean result = expression.getValue(context(event), Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.warn("Alert rule condition '{}' failed to evaluate - treating as no match: {}",
                    conditionExpression, e.getMessage());
            return false;
        }
    }
}
