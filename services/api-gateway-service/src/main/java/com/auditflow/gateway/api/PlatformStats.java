package com.auditflow.gateway.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The platform as a whole over a window, for operators: totals, one bucket
 * per UTC day (zero-filled, oldest first) with the events split per
 * customer, and the customers ranked by volume.
 *
 * @param byCustomer customer id to display name (null when unregistered), for the chart legend
 */
public record PlatformStats(Stats.Window window, Totals totals, List<DayBucket> perDay,
                            List<CustomerCount> topCustomers, Map<String, String> byCustomer) {

    public record Totals(long events, long alerts, long customers) {
    }

    public record DayBucket(LocalDate day, long events, long alerts, Map<String, Long> eventsByCustomer) {
    }

    public record CustomerCount(String customerId, String name, long events, long alerts) {
    }
}
