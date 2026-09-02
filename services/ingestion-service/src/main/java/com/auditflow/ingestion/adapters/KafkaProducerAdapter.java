package com.auditflow.ingestion.adapters;

import com.auditflow.common.model.AuditEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes validated audit events to the raw ingestion topic for
 * consumption by enrichment-service - and does not return until the
 * broker has acknowledged the write.
 *
 * <p>Why synchronous: the HTTP 202 this service returns is a promise that
 * the event is durable. A fire-and-forget send would make that promise
 * before Kafka had the record, and an event posted just before a broker
 * outage would vanish with a success code. Waiting for the ack (with
 * acks=all and an idempotent producer, see {@link KafkaProducerConfig})
 * turns the endpoint into a real at-least-once hop: either the record is
 * replicated, or the caller gets an error and retries.
 *
 * <p>This is not an outbox. The outbox pattern makes a database write and a
 * publish atomic; ingestion has no database write to pair with, so an
 * acknowledged publish is the correct guarantee here, not a shortcut.
 */
@Component
public class KafkaProducerAdapter {

    private final KafkaTemplate<String, AuditEvent> kafkaTemplate;
    private final String topic;
    private final Duration ackTimeout;

    public KafkaProducerAdapter(KafkaTemplate<String, AuditEvent> kafkaTemplate,
                                 @Value("${audit.ingestion.topic:audit-events}") String topic,
                                 @Value("${audit.ingestion.ack-timeout:10s}") Duration ackTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.ackTimeout = ackTimeout;
    }

    /**
     * Blocks until the broker acknowledges the record.
     *
     * @throws PublishFailedException when the send fails or the ack does not
     *                                arrive in time
     */
    public void publish(AuditEvent event) {
        try {
            kafkaTemplate.send(topic, event.getCustomerId(), event)
                    .get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishFailedException(event.getEventId(), e);
        } catch (ExecutionException e) {
            throw new PublishFailedException(event.getEventId(), e.getCause() != null ? e.getCause() : e);
        } catch (TimeoutException e) {
            throw new PublishFailedException(event.getEventId(), e);
        }
    }
}
