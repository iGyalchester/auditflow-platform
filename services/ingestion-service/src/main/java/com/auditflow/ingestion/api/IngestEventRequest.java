package com.auditflow.ingestion.api;

import com.auditflow.common.enums.EventType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Wire payload accepted by {@link EventIngestionController}. Kept separate
 * from {@link com.auditflow.common.model.AuditEvent} so the public API
 * contract can evolve independently of the internal domain model.
 *
 * <p>The size limits mirror the columns in auditflow-schema.sql. Without
 * them an oversized field is accepted, acknowledged with a 202, published
 * to Kafka, and only fails at the Aurora insert - by which point the source
 * has been told the event is safely stored, the S3 evidence copy may
 * already exist, and the failure is a consumer-side retry loop rather than
 * a 400 the sender can act on. Fail at the door instead.
 */
public record IngestEventRequest(
        @NotBlank @Size(max = 64) String eventId,
        @NotBlank @Size(max = 64) String customerId,
        @Size(max = 64) String userId,
        @Size(max = 64) String sessionId,
        @NotNull EventType type,
        @Size(max = 512) String resource,
        @Size(max = 128) String action,
        // not stored in audit_events, but bounded so one caller cannot make
        // a Kafka record of arbitrary size
        @Size(max = 4000) String query,
        @Size(max = 45) String ipAddress,
        @Size(max = 512) String userAgent,
        @Size(max = 8000) String rawLog
) {
}
