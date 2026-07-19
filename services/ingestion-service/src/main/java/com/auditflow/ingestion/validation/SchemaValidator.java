package com.auditflow.ingestion.validation;

import com.auditflow.common.model.AuditEvent;
import org.springframework.stereotype.Component;

/**
 * Validates a normalized {@link AuditEvent} before it is handed to
 * transport. Bean-validation on {@code IngestEventRequest} covers wire-level
 * shape; this covers domain-level invariants.
 */
@Component
public class SchemaValidator {

    public void validate(AuditEvent event) {
        if (event.getEventId() == null || event.getEventId().isBlank()) {
            throw new IllegalArgumentException("eventId is required");
        }
        if (event.getCustomerId() == null || event.getCustomerId().isBlank()) {
            throw new IllegalArgumentException("customerId is required");
        }
        if (event.getType() == null) {
            throw new IllegalArgumentException("type is required");
        }
        if (event.getTimestamp() == null) {
            throw new IllegalArgumentException("timestamp is required");
        }
    }
}
