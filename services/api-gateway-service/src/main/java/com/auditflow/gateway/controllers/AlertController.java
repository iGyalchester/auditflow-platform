package com.auditflow.gateway.controllers;

import com.auditflow.common.rules.AlertRuleRows;
import com.auditflow.gateway.api.AlertDetail;
import com.auditflow.gateway.data.AlertHistoryRepository;
import com.auditflow.gateway.data.AlertHistoryRepository.AlertFilter;
import com.auditflow.gateway.data.AlertHistoryRepository.AlertRow;
import com.auditflow.gateway.data.AuditLogRepository;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Alerts that fired for the calling customer, newest first.
 *
 * <pre>GET /api/v1/alerts?ruleId=...&amp;from=...&amp;to=...&amp;limit=50</pre>
 */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertHistoryRepository repository;
    private final AuditLogRepository auditLogs;
    private final RequestScope scope;

    public AlertController(AlertHistoryRepository repository, AuditLogRepository auditLogs, RequestScope scope) {
        this.repository = repository;
        this.auditLogs = auditLogs;
        this.scope = scope;
    }

    @GetMapping
    public List<AlertRow> list(HttpServletRequest request,
                               @RequestParam(name = "ruleId", required = false) String ruleId,
                               @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                               @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                               @RequestParam(name = "limit", required = false) Integer limit) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to");
        }
        AlertFilter filter = new AlertFilter(ruleId == null || ruleId.isBlank() ? null : ruleId.trim(), from, to);
        return repository.find(scope.customerId(request), filter, scope.limit(limit));
    }

    /**
     * The alert with the event that raised it and the delivery picture.
     * {@code event} is null when the event has since been purged by
     * retention - the alert itself is evidence and outlives it.
     */
    @GetMapping("/{alertId}")
    public AlertDetail get(HttpServletRequest request, @PathVariable("alertId") String alertId) {
        String customerId = scope.customerId(request);
        AlertRow alert = repository.findOne(customerId, alertId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such alert"));
        List<String> configured = AlertRuleRows.splitChannels(alert.ruleChannels());
        List<String> notified = AlertRuleRows.splitChannels(alert.notifiedChannels());
        List<String> undelivered = new ArrayList<>(configured);
        undelivered.removeAll(notified);
        return new AlertDetail(alert, auditLogs.findOne(customerId, alert.eventId()).orElse(null),
                configured, notified, undelivered);
    }
}
