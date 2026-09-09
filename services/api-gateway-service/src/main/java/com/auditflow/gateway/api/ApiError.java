package com.auditflow.gateway.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * The one error shape every gateway response uses, so the console parses
 * one thing: a short machine-readable {@code error} code, a sentence for
 * a person, and for validation failures the offending fields.
 *
 * @param error   {@code validation}, {@code bad_request}, {@code unauthenticated},
 *                {@code forbidden}, {@code not_found}, {@code too_many_events},
 *                {@code internal}
 * @param message plain-language explanation, safe to show
 * @param fields  field name to problem, for {@code validation} only
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String error, String message, Map<String, String> fields) {

    public static ApiError of(String error, String message) {
        return new ApiError(error, message, null);
    }
}
