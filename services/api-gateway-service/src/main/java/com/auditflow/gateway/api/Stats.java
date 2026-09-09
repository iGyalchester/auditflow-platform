package com.auditflow.gateway.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Everything the dashboard shows, in one response, for one customer and
 * one time window. Counts are over {@code [from, to)}; {@code previous}
 * covers the window of the same length just before it, for deltas.
 *
 * @param perDay    one bucket per UTC day in the window, zero-filled, oldest first
 * @param byRisk    events per risk level (every level present, zero when none)
 * @param byControl events per {@code FRAMEWORK:CONTROL}, most frequent first
 */
public record Stats(Window window, Totals totals, Totals previous, List<DayBucket> perDay,
                    Map<String, Long> byType, Map<String, Long> byRisk, Map<String, Long> byControl,
                    List<NameCount> topUsers, List<NameCount> topResources) {

    public record Window(Instant from, Instant to) {
    }

    /**
     * @param users distinct user ids seen in the window
     */
    public record Totals(long events, long alerts, long critical, long anomalous, long users) {
    }

    /**
     * @param byRisk events that day per risk level, every level present
     */
    public record DayBucket(LocalDate day, long events, long alerts, Map<String, Long> byRisk) {
    }

    public record NameCount(String name, long count) {
    }
}
