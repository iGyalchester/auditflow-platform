package com.auditflow.ingestion.api;

import com.auditflow.common.model.AuditEvent;
import com.auditflow.ingestion.adapters.KafkaProducerAdapter;
import com.auditflow.ingestion.adapters.PublishFailedException;
import com.auditflow.ingestion.security.IngestTokenFilter;
import com.auditflow.ingestion.validation.SchemaValidator;
import com.auditflow.ingestion.validation.TenantMismatchException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/events")
public class EventIngestionController {

    private static final Logger log = LoggerFactory.getLogger(EventIngestionController.class);

    private final SchemaValidator schemaValidator;
    private final KafkaProducerAdapter kafkaProducerAdapter;

    public EventIngestionController(SchemaValidator schemaValidator, KafkaProducerAdapter kafkaProducerAdapter) {
        this.schemaValidator = schemaValidator;
        this.kafkaProducerAdapter = kafkaProducerAdapter;
    }

    /**
     * 202 means "Kafka has it" - the adapter waits for the broker ack. If
     * that ack does not come, the answer is 503 with Retry-After so the
     * source re-sends; the collector agent holds its checkpoint on any
     * non-2xx, which is what makes the pull path at-least-once end to end.
     */
    @PostMapping
    public ResponseEntity<Void> ingest(
            @Valid @RequestBody IngestEventRequest request,
            // set by IngestTokenFilter; absent only when ingestion runs open
            @RequestAttribute(name = IngestTokenFilter.TENANT_ATTRIBUTE, required = false) String boundTenant) {
        AuditEvent event = toAuditEvent(request);
        schemaValidator.validate(event, boundTenant);
        kafkaProducerAdapter.publish(event);
        return ResponseEntity.accepted().build();
    }

    /**
     * Authenticated, well formed, and not allowed: the token belongs to a
     * different customer than the event claims. Logged at WARN because a
     * source trying to write as somebody else is worth seeing.
     */
    @ExceptionHandler(TenantMismatchException.class)
    public ResponseEntity<Map<String, String>> tenantMismatch(TenantMismatchException e) {
        log.warn("Rejected event: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "customerId does not match the tenant bound to the presented token"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidEvent(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(PublishFailedException.class)
    public ResponseEntity<Map<String, String>> publishFailed(PublishFailedException e) {
        log.warn("Event not accepted: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(Map.of("error", "event not durably stored, retry", "detail", e.getMessage()));
    }

    /**
     * How far ahead of our clock a source's timestamp may sit before we stop
     * believing it. Small clock skew between a source and us is normal and
     * harmless; an event dated next week is either a broken clock or a
     * source trying to park evidence outside the window a report will look
     * at, and neither should be stored silently.
     */
    static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private AuditEvent toAuditEvent(IngestEventRequest request) {
        return AuditEvent.builder()
                .eventId(request.eventId())
                .customerId(request.customerId())
                .userId(request.userId())
                .sessionId(request.sessionId())
                .type(request.type())
                .resource(request.resource())
                .action(request.action())
                .query(request.query())
                .ipAddress(request.ipAddress())
                .userAgent(request.userAgent())
                .rawLog(request.rawLog())
                .timestamp(occurredAt(request))
                .build();
    }

    /**
     * The source's own event time when it sent one, otherwise arrival time.
     * A timestamp in the past is always accepted - a backlog being drained
     * after an outage is the normal case, and refusing it would be refusing
     * the evidence we most want.
     */
    private Instant occurredAt(IngestEventRequest request) {
        if (request.occurredAt() == null) {
            return Instant.now();
        }
        if (request.occurredAt().isAfter(Instant.now().plus(MAX_CLOCK_SKEW))) {
            throw new IllegalArgumentException("occurredAt is in the future");
        }
        return request.occurredAt();
    }
}
