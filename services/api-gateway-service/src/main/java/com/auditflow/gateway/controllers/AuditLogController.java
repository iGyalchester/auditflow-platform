package com.auditflow.gateway.controllers;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.gateway.api.AuditLogDetail;
import com.auditflow.gateway.data.AlertHistoryRepository;
import com.auditflow.gateway.data.AuditLogRepository;
import com.auditflow.gateway.data.AuditLogRepository.AuditLogFilter;
import com.auditflow.gateway.data.AuditLogRepository.AuditLogRow;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * A customer's audit events, newest first. Filters are optional; the
 * customer is not - it comes from the security layer, never the caller.
 *
 * <pre>GET /api/v1/audit-logs?type=AUTH_EVENT&amp;riskLevel=HIGH&amp;userId=boris&amp;anomalous=true&amp;q=login&amp;from=...&amp;to=...&amp;limit=50</pre>
 *
 * Paging is keyset: pass the oldest {@code occurredAt} you have as
 * {@code to} to get the page before it. There is no offset paging - an
 * offset deep into a tenant's history costs the database the whole walk
 * every time, and the explorer only ever asks for "older".
 */
@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditLogController {

    static final int MAX_QUERY_LENGTH = 100;

    private final AuditLogRepository repository;
    private final AlertHistoryRepository alerts;
    private final RequestScope scope;

    public AuditLogController(AuditLogRepository repository, AlertHistoryRepository alerts, RequestScope scope) {
        this.repository = repository;
        this.alerts = alerts;
        this.scope = scope;
    }

    @GetMapping
    public List<AuditLogRow> list(HttpServletRequest request,
                                  @RequestParam(name = "type", required = false) String type,
                                  @RequestParam(name = "riskLevel", required = false) String riskLevel,
                                  @RequestParam(name = "userId", required = false) String userId,
                                  @RequestParam(name = "anomalous", required = false) Boolean anomalous,
                                  @RequestParam(name = "q", required = false) String q,
                                  @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                  @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                  @RequestParam(name = "limit", required = false) Integer limit) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to");
        }
        if (q != null && q.length() > MAX_QUERY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "q is longer than " + MAX_QUERY_LENGTH + " characters");
        }
        AuditLogFilter filter = new AuditLogFilter(
                oneOf("type", type, EventType.values()),
                oneOf("riskLevel", riskLevel, RiskLevel.values()),
                blankToNull(userId), anomalous, blankToNull(q), from, to);
        return repository.find(scope.customerId(request), filter, scope.limit(limit));
    }

    @GetMapping("/{eventId}")
    public AuditLogDetail get(HttpServletRequest request, @PathVariable("eventId") String eventId) {
        String customerId = scope.customerId(request);
        AuditLogRow event = repository.findOne(customerId, eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such event"));
        return new AuditLogDetail(event, alerts.findForEvent(customerId, eventId));
    }

    /** An enum-valued filter: null when absent, a 400 naming the choices when not one of them. */
    static String oneOf(String name, String value, Enum<?>[] allowed) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String wanted = value.trim().toUpperCase();
        if (Arrays.stream(allowed).noneMatch(e -> e.name().equals(wanted))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "'" + name + "' must be one of " + Arrays.toString(allowed));
        }
        return wanted;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
