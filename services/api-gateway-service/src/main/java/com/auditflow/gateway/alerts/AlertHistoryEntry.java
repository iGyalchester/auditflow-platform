package com.auditflow.gateway.alerts;

import java.time.Instant;

/**
 * API response view of a triggered alert, joined with its rule's name so the
 * response is meaningful without a second lookup.
 */
public record AlertHistoryEntry(
        String alertId,
        String ruleId,
        String ruleName,
        String eventId,
        String customerId,
        Instant triggeredAt,
        String notifiedChannels) {
}
