package com.auditflow.gateway.controllers;

import com.auditflow.gateway.security.CurrentCustomer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Controller helpers: the tenant every query is scoped by, and the page size. */
@Component
public class RequestScope {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 1000;

    private final CurrentCustomer currentCustomer;

    public RequestScope(CurrentCustomer currentCustomer) {
        this.currentCustomer = currentCustomer;
    }

    /** With auth enforced this is the token's claim; in dev the X-Customer-Id header. */
    public String customerId(HttpServletRequest request) {
        return currentCustomer.customerId(request).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "no customer for this request (auth disabled? send X-Customer-Id)"));
    }

    public int limit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        if (requested < 1 || requested > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_LIMIT);
        }
        return requested;
    }
}
