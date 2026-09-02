package com.auditflow.gateway.controllers;

import com.auditflow.gateway.data.AlertHistoryRepository;
import com.auditflow.gateway.data.AlertHistoryRepository.AlertRow;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Alerts that fired for the calling customer, newest first. */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertHistoryRepository repository;
    private final RequestScope scope;

    public AlertController(AlertHistoryRepository repository, RequestScope scope) {
        this.repository = repository;
        this.scope = scope;
    }

    @GetMapping
    public List<AlertRow> list(HttpServletRequest request, @RequestParam(name = "limit", required = false) Integer limit) {
        return repository.find(scope.customerId(request), scope.limit(limit));
    }
}
