package com.auditflow.ingestion.api;

import com.auditflow.common.model.AuditEvent;
import com.auditflow.ingestion.adapters.KafkaProducerAdapter;
import com.auditflow.ingestion.adapters.PublishFailedException;
import com.auditflow.ingestion.validation.SchemaValidator;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    public ResponseEntity<Void> ingest(@Valid @RequestBody IngestEventRequest request) {
        AuditEvent event = toAuditEvent(request);
        schemaValidator.validate(event);
        kafkaProducerAdapter.publish(event);
        return ResponseEntity.accepted().build();
    }

    @ExceptionHandler(PublishFailedException.class)
    public ResponseEntity<Map<String, String>> publishFailed(PublishFailedException e) {
        log.warn("Event not accepted: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(Map.of("error", "event not durably stored, retry", "detail", e.getMessage()));
    }

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
                .timestamp(Instant.now())
                .build();
    }
}
