package com.auditflow.gateway.controllers;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Proxies report generation to reporting-service, passing downstream error
 * responses (unknown framework, malformed window) through unchanged.
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final RestClient restClient;

    public ReportController(RestClient.Builder restClientBuilder,
                            @Value("${gateway.reporting.base-url:http://localhost:8084}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    @GetMapping
    public ResponseEntity<byte[]> generate(
            @RequestParam("customerId") String customerId,
            @RequestParam("framework") String framework,
            @RequestParam("from") String from,
            @RequestParam("to") String to) {
        try {
            return restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/v1/reports")
                            .queryParam("customerId", customerId)
                            .queryParam("framework", framework)
                            .queryParam("from", from)
                            .queryParam("to", to)
                            .build())
                    .retrieve()
                    .toEntity(byte[].class);
        } catch (RestClientResponseException e) {
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsByteArray());
        }
    }
}
