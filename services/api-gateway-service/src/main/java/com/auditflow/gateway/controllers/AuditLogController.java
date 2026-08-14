package com.auditflow.gateway.controllers;

import com.auditflow.gateway.audit.AuditLogEntry;
import com.auditflow.gateway.audit.AuditLogRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditLogController {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;

    private final AuditLogRepository auditLogRepository;

    public AuditLogController(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Lists the most recent audit events for one customer, newest first.
     *
     * <p>{@code customerId} is a required request parameter for now; once
     * {@code JwtAuthFilter} verifies tokens and exposes claims, it should be
     * sourced from the authenticated principal instead of the caller so a
     * tenant can't read another tenant's events by changing the query string.
     */
    @GetMapping
    public List<AuditLogEntry> list(
            @RequestParam("customerId") String customerId,
            @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit) {
        int cappedLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return auditLogRepository.findByCustomer(customerId, cappedLimit);
    }
}
