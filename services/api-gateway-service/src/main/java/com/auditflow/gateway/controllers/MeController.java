package com.auditflow.gateway.controllers;

import com.auditflow.gateway.security.CurrentCustomer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who am I, as the gateway sees it - the quickest way for a client (or a
 * person with curl) to confirm their token is accepted and which tenant it
 * scopes them to.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    public record Me(String customerId, String subject) {
    }

    private final CurrentCustomer currentCustomer;

    public MeController(CurrentCustomer currentCustomer) {
        this.currentCustomer = currentCustomer;
    }

    @GetMapping
    public Me me(HttpServletRequest request) {
        return new Me(
                currentCustomer.customerId(request).orElse(null),
                currentCustomer.subject().orElse(null));
    }
}
