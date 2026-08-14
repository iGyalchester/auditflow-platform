package com.auditflow.gateway.controllers;

import com.auditflow.gateway.alerts.AlertHistoryEntry;
import com.auditflow.gateway.alerts.AlertHistoryRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;

    private final AlertHistoryRepository alertHistoryRepository;

    public AlertController(AlertHistoryRepository alertHistoryRepository) {
        this.alertHistoryRepository = alertHistoryRepository;
    }

    /**
     * Lists the most recent triggered alerts for one customer, newest first.
     * As with audit-logs, customerId moves to the verified JWT principal once
     * JwtAuthFilter does signature verification.
     */
    @GetMapping
    public List<AlertHistoryEntry> list(
            @RequestParam("customerId") String customerId,
            @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit) {
        int cappedLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return alertHistoryRepository.findByCustomer(customerId, cappedLimit);
    }
}
