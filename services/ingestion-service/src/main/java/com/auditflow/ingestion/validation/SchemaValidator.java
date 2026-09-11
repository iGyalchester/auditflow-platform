package com.auditflow.ingestion.validation;

import com.auditflow.common.model.AuditEvent;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Validates a normalized {@link AuditEvent} before it is handed to
 * transport. Bean-validation on {@code IngestEventRequest} covers wire-level
 * shape; this covers domain-level invariants.
 */
@Component
public class SchemaValidator {

    /** For callers with no authenticated tenant (open mode, and unit tests). */
    public void validate(AuditEvent event) {
        validate(event, null);
    }

    /**
     * @param boundCustomerId the customer the presented token belongs to, or
     *        null when ingestion is running open. A non-null value that does
     *        not match the event is a {@link TenantMismatchException}: this
     *        is the check that stops one source forging another customer's
     *        audit trail.
     */
    public void validate(AuditEvent event, @Nullable String boundCustomerId) {
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
        if (boundCustomerId != null && !boundCustomerId.equals(event.getCustomerId())) {
            throw new TenantMismatchException(boundCustomerId, event.getCustomerId());
        }
    }
}
