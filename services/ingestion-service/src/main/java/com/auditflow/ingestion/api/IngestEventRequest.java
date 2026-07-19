package com.auditflow.ingestion.api;

import com.auditflow.common.enums.EventType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Wire payload accepted by {@link EventIngestionController}. Kept separate
 * from {@link com.auditflow.common.model.AuditEvent} so the public API
 * contract can evolve independently of the internal domain model.
 */
public record IngestEventRequest(
        @NotBlank String eventId,
        @NotBlank String customerId,
        String userId,
        String sessionId,
        @NotNull EventType type,
        String resource,
        String action,
        String query,
        String ipAddress,
        String userAgent,
        String rawLog
) {
}
