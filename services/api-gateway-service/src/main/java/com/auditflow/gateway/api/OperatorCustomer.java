package com.auditflow.gateway.api;

import java.time.Instant;

/**
 * One row of the operator's customers table. A tenant appears here as
 * soon as anything mentions it - a row in {@code customers}, an event, or
 * a rule - because a source can start pushing events for a tenant nobody
 * registered yet, and the operator needs to see that, not miss it.
 *
 * @param name        from {@code customers}, null when the tenant was never registered
 * @param lastEventAt null when no event has ever arrived
 */
public record OperatorCustomer(String customerId, String name, long events24h, long events7d, long alerts7d,
                               long rules, Instant lastEventAt) {
}
