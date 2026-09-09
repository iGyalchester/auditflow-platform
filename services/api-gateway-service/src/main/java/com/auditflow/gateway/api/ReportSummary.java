package com.auditflow.gateway.api;

import java.time.Instant;
import java.util.Map;

/**
 * What a framework report would contain, as numbers, before anyone
 * downloads it: the events classified for the framework in the window,
 * split by its controls, by risk, and by event type.
 */
public record ReportSummary(String framework, Instant from, Instant to, int events,
                            Map<String, Long> byControl, Map<String, Long> byRisk, Map<String, Long> byType) {
}
