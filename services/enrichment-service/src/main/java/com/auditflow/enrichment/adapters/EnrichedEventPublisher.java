package com.auditflow.enrichment.adapters;

import com.auditflow.common.model.AuditEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes fully-enriched events to the topic alerting-service consumes,
 * keyed by customer so one tenant's events stay ordered.
 */
@Component
public class EnrichedEventPublisher {

    private final KafkaTemplate<String, AuditEvent> kafkaTemplate;
    private final String topic;

    public EnrichedEventPublisher(KafkaTemplate<String, AuditEvent> enrichedEventKafkaTemplate,
                                  @Value("${audit.enrichment.topic:enriched-events}") String topic) {
        this.kafkaTemplate = enrichedEventKafkaTemplate;
        this.topic = topic;
    }

    public void publish(AuditEvent event) {
        kafkaTemplate.send(topic, event.getCustomerId(), event);
    }
}
