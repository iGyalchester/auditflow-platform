package com.auditflow.agent.publish;

import com.auditflow.common.model.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Forwards collected events through ingestion-service's authenticated
 * front door - the agent gets no special path into Kafka or the stores,
 * it presents the same X-Audit-Token as any other source. A failed post
 * is logged and reported so the runner can hold the checkpoint and retry
 * the batch on the next poll.
 */
@Component
public class IngestionPublisher {

    private static final Logger log = LoggerFactory.getLogger(IngestionPublisher.class);

    private final RestClient restClient;

    public IngestionPublisher(@Value("${agent.ingestion.url}") String ingestionUrl,
                              @Value("${agent.ingestion.token:}") String token) {
        RestClient.Builder builder = RestClient.builder().baseUrl(ingestionUrl);
        if (!token.isBlank()) {
            builder.defaultHeader("X-Audit-Token", token);
        }
        this.restClient = builder.build();
    }

    /** Returns true when every event in the batch was accepted. */
    public boolean publish(Iterable<AuditEvent> events) {
        for (AuditEvent event : events) {
            try {
                restClient.post()
                        .uri("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(toRequest(event))
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception e) {
                log.warn("Failed to publish audit event {}: {}", event.getEventId(), e.getMessage());
                return false;
            }
        }
        return true;
    }

    // ingestion-service's IngestEventRequest wire shape
    private static Map<String, Object> toRequest(AuditEvent event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", event.getEventId());
        body.put("customerId", event.getCustomerId());
        body.put("userId", event.getUserId());
        body.put("type", event.getType().name());
        body.put("resource", event.getResource());
        body.put("action", event.getAction());
        body.put("query", event.getQuery());
        return body;
    }
}
