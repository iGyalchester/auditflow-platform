package com.auditflow.gateway.controllers;

import com.auditflow.common.interfaces.ReportGenerator;
import com.auditflow.common.model.AuditEvent;
import com.auditflow.common.model.ComplianceControl;
import com.auditflow.gateway.api.ReportSummary;
import com.auditflow.gateway.data.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
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
    static final Duration MAX_WINDOW = Duration.ofDays(366);

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

    /**
     * The customer id reaches the filename, and it comes from a JWT claim we
     * do not control the shape of. Built through ContentDisposition rather
     * than string concatenation so a quote or a newline in it cannot break
     * out of the header, and reduced to safe characters first so the
     * filename stays a filename.
     */
    private static String contentDisposition(ReportGenerator generator, String customerId, Instant start) {
        String safeCustomer = customerId.replaceAll("[^A-Za-z0-9._-]", "_");
        String filename = "%s-%s-%s.txt".formatted(generator.framework().toLowerCase(Locale.ROOT),
                safeCustomer, start.toString().substring(0, 10));
        return ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString();
    }

    @GetMapping(value = "/{framework}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<byte[]> report(HttpServletRequest request,
                                         @PathVariable("framework") String framework,
                                         @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                         @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        ReportGenerator generator = generator(framework);
        TimeWindow window = window(from, to);
        String customerId = scope.customerId(request);
        List<AuditEvent> events = load(customerId, generator, window);
        byte[] body = generator.generate(customerId, window.from(), window.to(), events);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(generator, customerId, window.from()))
                .contentType(MediaType.TEXT_PLAIN)
                .body(body);
    }

    /**
     * The report as numbers, for the console's preview: the same events the
     * download would contain (same window, same cap), counted by the
     * framework's controls, by risk, and by event type.
     */
    @GetMapping("/{framework}/summary")
    public ReportSummary summary(HttpServletRequest request,
                                 @PathVariable("framework") String framework,
                                 @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                 @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        ReportGenerator generator = generator(framework);
        TimeWindow window = window(from, to);
        List<AuditEvent> events = load(scope.customerId(request), generator, window);
        Map<String, Long> byControl = new LinkedHashMap<>();
        Map<String, Long> byRisk = new LinkedHashMap<>();
        Map<String, Long> byType = new LinkedHashMap<>();
        for (AuditEvent event : events) {
            for (ComplianceControl control : event.getControls()) {
                if (generator.framework().equals(control.getFramework())) {
                    byControl.merge(control.getControlId(), 1L, Long::sum);
                }
            }
            byRisk.merge(event.getRiskLevel() == null ? "UNKNOWN" : event.getRiskLevel().name(), 1L, Long::sum);
            byType.merge(event.getType() == null ? "UNKNOWN" : event.getType().name(), 1L, Long::sum);
        }
        return new ReportSummary(generator.framework(), window.from(), window.to(), events.size(),
                sortedByCount(byControl), sortedByCount(byRisk), sortedByCount(byType));
    }

    private ReportGenerator generator(String framework) {
        ReportGenerator generator = generators.get(framework.toLowerCase(Locale.ROOT));
        if (generator == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no report for '" + framework + "' (have " + frameworks() + ")");
        }
        return generator;
    }

    private static TimeWindow window(Instant from, Instant to) {
        return TimeWindow.resolve(from, to, DEFAULT_WINDOW, MAX_WINDOW);
    }

    /**
     * The cap counts the events this report will contain, not every event
     * in the window. Before the framework filter reached SQL, a tenant
     * with 10,001 events and 50 SOC 2 events got a 413 for a report that
     * would have been fifty lines long.
     */
    private List<AuditEvent> load(String customerId, ReportGenerator generator, TimeWindow window) {
        List<AuditEvent> events = repository.findForReport(
                customerId, generator.framework(), window.from(), window.to(), MAX_EVENTS + 1);
        if (events.size() > MAX_EVENTS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "window has more than " + MAX_EVENTS + " " + generator.framework()
                            + " events; narrow it");
        }
        return events;
    }

    private static Map<String, Long> sortedByCount(Map<String, Long> counts) {
        Map<String, Long> sorted = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted((a, b) -> b.getValue().equals(a.getValue())
                        ? a.getKey().compareTo(b.getKey()) : Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }
}
