package com.auditflow.ingestion.adapters;

import com.auditflow.common.model.AuditEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes validated audit events to the raw ingestion topic for
 * consumption by enrichment-service.
 */
@Component
public class KafkaProducerAdapter {

    private final KafkaTemplate<String, AuditEvent> kafkaTemplate;
    private final String topic;

    public KafkaProducerAdapter(KafkaTemplate<String, AuditEvent> kafkaTemplate,
                                 @Value("${audit.ingestion.topic:audit-events}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(AuditEvent event) {
        kafkaTemplate.send(topic, event.getCustomerId(), event);
    }
}
