package com.auditflow.gateway.controllers;

import com.auditflow.gateway.data.CustomerRepository;
import com.auditflow.gateway.security.CurrentCustomer;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * Who am I, as the gateway sees it - the quickest way for a client (or a
 * person with curl) to confirm their token is accepted, which tenant it
 * scopes them to, and which roles it carries. The console calls it once
 * after sign-in and again when an operator switches the customer they act
 * as.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    /**
     * @param customerId   the caller's own tenant
     * @param customerName its display name from {@code customers}, null when unknown
     * @param subject      the token subject (null in dev)
     * @param roles        {@code USER}, plus {@code OPERATOR} for platform operators
     * @param actingAs     the tenant an operator is currently acting as, when different
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Me(String customerId, String customerName, String subject, Set<String> roles,
                     String actingAs, String actingAsName) {
    }

    private final CurrentCustomer currentCustomer;
    private final RequestScope scope;
    private final CustomerRepository customers;

    public MeController(CurrentCustomer currentCustomer, RequestScope scope, CustomerRepository customers) {
        this.currentCustomer = currentCustomer;
        this.scope = scope;
        this.customers = customers;
    }

    @GetMapping
    public Me me(HttpServletRequest request) {
        String own = scope.ownCustomerId();
        String acting = scope.actingAs(request).filter(id -> !id.equals(own)).orElse(null);
        return new Me(
                own,
                customers.findName(own).orElse(null),
                currentCustomer.subject().orElse(null),
                currentCustomer.roles(),
                acting,
                acting == null ? null : customers.findName(acting).orElse(null));
    }
}
