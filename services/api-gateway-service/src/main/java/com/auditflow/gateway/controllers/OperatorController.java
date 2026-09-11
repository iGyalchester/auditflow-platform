package com.auditflow.gateway.controllers;

import com.auditflow.gateway.api.OperatorCustomer;
import com.auditflow.gateway.api.PlatformStats;
import com.auditflow.gateway.data.OperatorRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * The platform operator's view across every tenant. Two gates, one
 * rule: the path ({@code /api/v1/operator/**} requires
 * {@code ROLE_OPERATOR} in {@code SecurityConfig}, in both modes) and
 * the class ({@code @PreAuthorize}, so the gate travels with the code
 * that sees every tenant even if someone mounts it elsewhere). Nothing
 * here takes a customer from the request - these are the only queries
 * in the gateway that see every tenant at once.
 */
@RestController
@RequestMapping("/api/v1/operator")
@PreAuthorize("hasRole('OPERATOR')")
public class OperatorController {

    private final OperatorRepository repository;

    public OperatorController(OperatorRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/customers")
    public List<OperatorCustomer> customers() {
        return repository.customers(Instant.now());
    }

    @GetMapping("/stats")
    public PlatformStats stats(@RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                               @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        TimeWindow window = TimeWindow.resolve(from, to, StatsController.DEFAULT_WINDOW, TimeWindow.MAX_LENGTH);
        return repository.stats(window.from(), window.to());
    }
}
