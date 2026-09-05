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
        this(RestClient.builder(), ingestionUrl, token);
    }

    /**
     * Same wiring, but over a builder the caller supplies - so a test can
     * bind a {@code MockRestServiceServer} to it and assert on the JSON that
     * actually goes out, rather than on a mock of our own code.
     */
    IngestionPublisher(RestClient.Builder builder, String ingestionUrl, String token) {
        builder.baseUrl(ingestionUrl);
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
        // When the statement was logged, not when we got round to reading
        // it. The agent polls, so these differ by up to one poll interval in
        // normal running and by the whole outage when catching up on a
        // backlog. Without this the catch-up minute would be stamped on
        // hours of history.
        //
        // Formatted here rather than handed to Jackson as an Instant: this
        // module has jackson-databind but not jackson-datatype-jsr310, so an
        // Instant would be introspected as a bean and go out as
        // {"epochSecond":...,"nano":...}, which the ingestion side cannot
        // read back. Instant.toString() is ISO-8601 by definition and owes
        // nothing to what is on the classpath.
        body.put("occurredAt", event.getTimestamp().toString());
        return body;
    }
}
