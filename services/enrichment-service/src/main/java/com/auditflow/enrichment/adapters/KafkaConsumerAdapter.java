package com.auditflow.enrichment.adapters;

import com.auditflow.common.interfaces.DataSink;
import com.auditflow.common.interfaces.EventProcessor;
import com.auditflow.common.model.AuditEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Consumes raw events from ingestion-service, runs them through the
 * ordered enrichment pipeline, and fans the result out to every
 * configured {@link DataSink}.
 */
@Component
public class KafkaConsumerAdapter {

    private final List<EventProcessor> processors;
    private final List<DataSink> sinks;
    private final EnrichedEventPublisher enrichedEventPublisher;

    public KafkaConsumerAdapter(List<EventProcessor> processors, List<DataSink> sinks,
                                EnrichedEventPublisher enrichedEventPublisher) {
        this.processors = processors.stream()
                .sorted(Comparator.comparingInt(EventProcessor::order))
                .toList();
        this.sinks = sinks;
        this.enrichedEventPublisher = enrichedEventPublisher;
    }

    @KafkaListener(topics = "${audit.ingestion.topic}")
    public void onMessage(AuditEvent rawEvent) {
        AuditEvent enriched = rawEvent;
        for (EventProcessor processor : processors) {
            enriched = processor.process(enriched);
        }
        for (DataSink sink : sinks) {
            sink.write(enriched);
        }
        // Published only after every sink accepted the event, so alerting
        // never fires on an event that failed to persist as evidence.
        enrichedEventPublisher.publish(enriched);
    }
}
