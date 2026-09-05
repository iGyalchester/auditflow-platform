package com.auditflow.common.rules;

import com.auditflow.common.enums.EventType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The list itself, away from SpEL. ConditionEvaluatorTest proves the
 * evaluator actually consults it; this pins what is on it.
 */
class AllowListMethodResolverTest {

    @Test
    void stringPredicatesAreAllowed() {
        for (String method : List.of("startsWith", "endsWith", "contains", "equals",
                "equalsIgnoreCase", "isEmpty", "isBlank", "length", "toLowerCase",
                "toUpperCase", "trim")) {
            assertThat(AllowListMethodResolver.isAllowed("a string", method))
                    .as("String.%s", method).isTrue();
        }
    }

    @Test
    void allocatingAndScanningStringMethodsAreNot() {
        for (String method : List.of("repeat", "matches", "replaceAll", "replace", "split",
                "getBytes", "toCharArray", "chars", "concat", "format", "intern",
                "hashCode", "getClass", "wait", "notify")) {
            assertThat(AllowListMethodResolver.isAllowed("a string", method))
                    .as("String.%s", method).isFalse();
        }
    }

    @Test
    void collectionsExposeReadsOnly() {
        assertThat(AllowListMethodResolver.isAllowed(List.of("a"), "contains")).isTrue();
        assertThat(AllowListMethodResolver.isAllowed(List.of("a"), "size")).isTrue();
        assertThat(AllowListMethodResolver.isAllowed(List.of("a"), "add")).isFalse();
        assertThat(AllowListMethodResolver.isAllowed(List.of("a"), "removeIf")).isFalse();
        assertThat(AllowListMethodResolver.isAllowed(List.of("a"), "stream")).isFalse();

        assertThat(AllowListMethodResolver.isAllowed(Map.of("k", "v"), "containsKey")).isTrue();
        assertThat(AllowListMethodResolver.isAllowed(Map.of("k", "v"), "get")).isTrue();
        assertThat(AllowListMethodResolver.isAllowed(Map.of("k", "v"), "put")).isFalse();
        assertThat(AllowListMethodResolver.isAllowed(Map.of("k", "v"), "clear")).isFalse();
    }

    @Test
    void enumsExposeNameOnly() {
        assertThat(AllowListMethodResolver.isAllowed(EventType.DATA_EXPORT, "name")).isTrue();
        assertThat(AllowListMethodResolver.isAllowed(EventType.DATA_EXPORT, "ordinal")).isFalse();
        assertThat(AllowListMethodResolver.isAllowed(EventType.DATA_EXPORT, "getDeclaringClass")).isFalse();
    }

    @Test
    void anUnlistedReceiverHasNoMethodsAtAll() {
        assertThat(AllowListMethodResolver.isAllowed(java.time.Instant.EPOCH, "toString")).isFalse();
        assertThat(AllowListMethodResolver.isAllowed(java.time.Instant.EPOCH, "getEpochSecond")).isFalse();
        assertThat(AllowListMethodResolver.isAllowed(Boolean.TRUE, "booleanValue")).isFalse();
    }
}
