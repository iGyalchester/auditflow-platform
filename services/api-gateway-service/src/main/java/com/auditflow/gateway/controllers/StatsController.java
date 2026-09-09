package com.auditflow.gateway.controllers;

import com.auditflow.gateway.api.Stats;
import com.auditflow.gateway.data.StatsRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

/**
 * The dashboard in one call.
 *
 * <pre>GET /api/v1/stats?from=2026-09-01T00:00:00Z&amp;to=2026-09-08T00:00:00Z</pre>
 *
 * Defaults to the last seven days; a window may span at most a year, so
 * the per-day series stays a few hundred points.
 */
@RestController
@RequestMapping("/api/v1/stats")
public class StatsController {

    static final Duration DEFAULT_WINDOW = Duration.ofDays(7);
    static final Duration MAX_WINDOW = Duration.ofDays(366);

    private final StatsRepository repository;
    private final RequestScope scope;

    public StatsController(StatsRepository repository, RequestScope scope) {
        this.repository = repository;
        this.scope = scope;
    }

    @GetMapping
    public Stats stats(HttpServletRequest request,
                       @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                       @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        TimeWindow window = TimeWindow.resolve(from, to, DEFAULT_WINDOW, MAX_WINDOW);
        return repository.stats(scope.customerId(request), window.from(), window.to());
    }
}
