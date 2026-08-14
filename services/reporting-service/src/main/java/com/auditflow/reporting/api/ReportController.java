package com.auditflow.reporting.api;

import com.auditflow.common.interfaces.ReportGenerator;
import com.auditflow.common.model.AuditEvent;
import com.auditflow.reporting.queries.EventQueryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final List<ReportGenerator> generators;
    private final EventQueryRepository eventQueryRepository;

    public ReportController(List<ReportGenerator> generators, EventQueryRepository eventQueryRepository) {
        this.generators = generators;
        this.eventQueryRepository = eventQueryRepository;
    }

    /**
     * Generates a framework-scoped evidence report for one customer over a
     * time window ({@code from}/{@code to} are ISO-8601 instants).
     */
    @GetMapping
    public ResponseEntity<byte[]> generate(
            @RequestParam("customerId") String customerId,
            @RequestParam("framework") String framework,
            @RequestParam("from") String from,
            @RequestParam("to") String to) {
        ReportGenerator generator = generators.stream()
                .filter(g -> g.framework().equalsIgnoreCase(framework))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unknown framework: " + framework + "; supported: "
                                + generators.stream().map(ReportGenerator::framework).toList()));

        Instant fromInstant;
        Instant toInstant;
        try {
            fromInstant = Instant.parse(from);
            toInstant = Instant.parse(to);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "from/to must be ISO-8601 instants, e.g. 2026-01-01T00:00:00Z");
        }

        List<AuditEvent> events = eventQueryRepository.findEvents(customerId, fromInstant, toInstant);
        byte[] report = generator.generate(customerId, fromInstant, toInstant, events);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(report);
    }
}
