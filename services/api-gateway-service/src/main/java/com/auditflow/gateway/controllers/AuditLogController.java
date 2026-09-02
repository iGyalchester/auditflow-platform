package com.auditflow.gateway.controllers;

import com.auditflow.gateway.data.AuditLogRepository;
import com.auditflow.gateway.data.AuditLogRepository.AuditLogRow;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * A customer's audit events, newest first. Filters are optional; the
 * customer is not - it comes from the security layer, never the caller.
 *
 * <pre>GET /api/v1/audit-logs?type=AUTH_EVENT&amp;from=2026-09-01T00:00:00Z&amp;to=...&amp;limit=50</pre>
 */
@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditLogController {

    private final AuditLogRepository repository;
    private final RequestScope scope;

    public AuditLogController(AuditLogRepository repository, RequestScope scope) {
        this.repository = repository;
        this.scope = scope;
    }

    @GetMapping
    public List<AuditLogRow> list(HttpServletRequest request,
                                  @RequestParam(name = "type", required = false) String type,
                                  @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                  @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                  @RequestParam(name = "limit", required = false) Integer limit) {
        return repository.find(scope.customerId(request), type, from, to, scope.limit(limit));
    }
}
