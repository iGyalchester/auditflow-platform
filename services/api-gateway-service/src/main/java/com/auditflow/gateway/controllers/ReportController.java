package com.auditflow.gateway.controllers;

import com.auditflow.common.interfaces.ReportGenerator;
import com.auditflow.common.model.AuditEvent;
import com.auditflow.gateway.data.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Framework evidence reports over the customer's stored events.
 *
 * <pre>GET /api/v1/reports/soc2?from=2026-08-01T00:00:00Z&amp;to=2026-09-01T00:00:00Z</pre>
 *
 * Defaults to the last 30 days. Reads Aurora (the queryable copy); the
 * S3/Athena lake path is for windows larger than {@value #MAX_EVENTS}
 * events and is not wired yet - a window that hits the cap is answered
 * with 413 rather than a silently truncated report.
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    static final int MAX_EVENTS = 10_000;
    static final Duration DEFAULT_WINDOW = Duration.ofDays(30);

    private final AuditLogRepository repository;
    private final RequestScope scope;
    private final Map<String, ReportGenerator> generators;

    public ReportController(AuditLogRepository repository, RequestScope scope, List<ReportGenerator> generators) {
        this.repository = repository;
        this.scope = scope;
        this.generators = generators.stream()
                .collect(Collectors.toMap(g -> g.framework().toLowerCase(Locale.ROOT), Function.identity()));
    }

    @GetMapping
    public List<String> frameworks() {
        return generators.keySet().stream().sorted().toList();
    }

    @GetMapping(value = "/{framework}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<byte[]> report(HttpServletRequest request,
                                         @PathVariable("framework") String framework,
                                         @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                         @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        ReportGenerator generator = generators.get(framework.toLowerCase(Locale.ROOT));
        if (generator == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no report for '" + framework + "' (have " + frameworks() + ")");
        }
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(DEFAULT_WINDOW);
        if (!start.isBefore(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to");
        }
        String customerId = scope.customerId(request);
        List<AuditEvent> events = repository.findForReport(customerId, start, end, MAX_EVENTS + 1);
        if (events.size() > MAX_EVENTS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "window has more than " + MAX_EVENTS + " events; narrow it");
        }
        byte[] body = generator.generate(customerId, start, end, events);
        String filename = "%s-%s-%s.txt".formatted(generator.framework().toLowerCase(Locale.ROOT), customerId,
                start.toString().substring(0, 10));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(body);
    }
}
