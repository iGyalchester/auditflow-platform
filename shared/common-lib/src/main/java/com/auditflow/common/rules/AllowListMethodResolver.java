package com.auditflow.common.rules;

import org.springframework.core.convert.TypeDescriptor;
import org.springframework.expression.AccessException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.MethodExecutor;
import org.springframework.expression.MethodResolver;
import org.springframework.expression.spel.support.DataBindingMethodResolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The only methods a customer's condition expression may call.
 *
 * <p>{@code SimpleEvaluationContext.withInstanceMethods()} allows <em>every</em>
 * public instance method on the values an expression can reach, which on a
 * String is more than it sounds. Three of them are enough to hurt a shared
 * consumer thread:
 *
 * <ul>
 *   <li>{@code resource.repeat(200000000)} asks for a String far larger than
 *       the heap. SpEL wraps the resulting OutOfMemoryError in an
 *       {@code ExpressionInvocationTargetException}, so the evaluator catches
 *       it and carries on - but only after the JVM has spent itself trying,
 *       with every other rule and every other tenant sharing that heap.</li>
 *   <li>{@code resource.matches('(a+)+$')} is a catastrophically backtracking
 *       regex: seconds to minutes of CPU on one event.</li>
 *   <li>{@code getBytes}, {@code toCharArray}, {@code chars} allocate copies
 *       of the input for free.</li>
 * </ul>
 *
 * <p>So the sandbox switches from "everything except type references and
 * constructors" to a list of what a predicate actually needs. Anything not
 * listed simply does not resolve, and SpEL reports EL1004E - the same
 * fail-closed path an unknown method already took.
 *
 * <p>Adding to this list is a deliberate act. Ask whether the method can
 * allocate or loop in proportion to its arguments before adding it.
 */
final class AllowListMethodResolver implements MethodResolver {

    private static final Map<Class<?>, Set<String>> ALLOWED = allowed();

    private final MethodResolver delegate = DataBindingMethodResolver.forInstanceMethodInvocation();

    private static Map<Class<?>, Set<String>> allowed() {
        Map<Class<?>, Set<String>> allowed = new LinkedHashMap<>();
        // read-only, bounded by the receiver that already exists
        allowed.put(String.class, Set.of(
                "startsWith", "endsWith", "contains", "equals", "equalsIgnoreCase",
                "isEmpty", "isBlank", "length", "toLowerCase", "toUpperCase", "trim"));
        allowed.put(List.class, Set.of("contains", "isEmpty", "size"));
        allowed.put(Map.class, Set.of("containsKey", "get", "isEmpty", "size"));
        // so a rule can say type.name() == 'DATA_EXPORT'
        allowed.put(Enum.class, Set.of("name"));
        return Map.copyOf(allowed);
    }

    @Override
    public MethodExecutor resolve(EvaluationContext context, Object targetObject, String name,
                                  List<TypeDescriptor> argumentTypes) throws AccessException {
        if (targetObject == null || !isAllowed(targetObject, name)) {
            return null;
        }
        // the delegate still applies its own rules: instance methods only,
        // nothing declared on Object, no statics
        return delegate.resolve(context, targetObject, name, argumentTypes);
    }

    /** Visible for testing: is this method callable on this kind of value? */
    static boolean isAllowed(Object target, String name) {
        for (Map.Entry<Class<?>, Set<String>> entry : ALLOWED.entrySet()) {
            if (entry.getKey().isInstance(target) && entry.getValue().contains(name)) {
                return true;
            }
        }
        return false;
    }
}
