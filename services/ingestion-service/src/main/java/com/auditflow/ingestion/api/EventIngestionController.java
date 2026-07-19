package com.auditflow.ingestion.api;

import com.auditflow.common.model.AuditEvent;
import com.auditflow.ingestion.adapters.KafkaProducerAdapter;
import com.auditflow.ingestion.validation.SchemaValidator;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/events")
public class EventIngestionController {

    private final SchemaValidator schemaValidator;
    private final KafkaProducerAdapter kafkaProducerAdapter;

    public EventIngestionController(SchemaValidator schemaValidator, KafkaProducerAdapter kafkaProducerAdapter) {
        this.schemaValidator = schemaValidator;
        this.kafkaProducerAdapter = kafkaProducerAdapter;
    }

    @PostMapping
    public ResponseEntity<Void> ingest(@Valid @RequestBody IngestEventRequest request) {
        AuditEvent event = toAuditEvent(request);
        schemaValidator.validate(event);
        kafkaProducerAdapter.publish(event);
        return ResponseEntity.accepted().build();
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
