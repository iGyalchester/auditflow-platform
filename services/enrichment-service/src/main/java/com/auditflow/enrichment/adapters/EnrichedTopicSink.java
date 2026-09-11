package com.auditflow.enrichment.adapters;

import com.auditflow.common.interfaces.DataSink;
import com.auditflow.common.model.AuditEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Republishes each enriched event - now carrying risk level, controls and
 * the anomaly flag - to the topic alerting-service consumes. Modelled as a
 * {@link DataSink} so it rides the same fan-out as S3 and Aurora, and
 * ordered last so an event is persisted before anyone is paged about it.
 * A send that is not acknowledged throws, which makes the listener retry
 * the record rather than silently skipping the alert.
 */
@Component
// Last, and an explicit number rather than LOWEST_PRECEDENCE: an unordered
// bean also sorts as LOWEST_PRECEDENCE, so "last" was a tie the container
// broke however it liked. Alerting must not fire on an event that is not
// yet stored - that is a page with no evidence behind it.
@Order(30)
public class EnrichedTopicSink implements DataSink {

    private final KafkaTemplate<String, AuditEvent> kafkaTemplate;
    private final String topic;
    private final Duration ackTimeout;

    public EnrichedTopicSink(KafkaTemplate<String, AuditEvent> enrichedEventKafkaTemplate,
                             @Value("${audit.enrichment.enriched-topic:audit-events-enriched}") String topic,
                             @Value("${audit.enrichment.ack-timeout:10s}") Duration ackTimeout) {
        this.kafkaTemplate = enrichedEventKafkaTemplate;
        this.topic = topic;
        this.ackTimeout = ackTimeout;
    }

    @Override
    public void write(AuditEvent event) {
        try {
            kafkaTemplate.send(topic, event.getCustomerId(), event).get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted republishing enriched event " + event.getEventId(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Enriched event " + event.getEventId() + " not acknowledged by Kafka", e);
        }
    }

    @Override
    public void writeBatch(List<AuditEvent> events) {
        events.forEach(this::write);
    }

    @Override
    public String sinkName() {
        return "kafka-enriched";
    }
}
