package com.auditflow.gateway.audit;

import java.time.Instant;

/**
 * API response view of a persisted audit event, mapped from the queryable
 * {@code audit_events} row that enrichment-service writes. Event type and
 * risk level are carried as raw strings rather than enums so an unrecognised
 * value stored by a newer producer can't break a read for older consumers.
 */
public record AuditLogEntry(
        String eventId,
        String customerId,
        String userId,
        String sessionId,
        Instant occurredAt,
        String eventType,
        String resource,
        String action,
        String riskLevel,
        boolean anomalous) {
}
