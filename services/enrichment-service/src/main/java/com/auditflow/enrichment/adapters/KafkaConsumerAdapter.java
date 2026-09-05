package com.auditflow.enrichment.adapters;

import com.auditflow.common.interfaces.DataSink;
import com.auditflow.common.interfaces.EventProcessor;
import com.auditflow.common.model.AuditEvent;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Consumes raw events from ingestion-service, runs them through the
 * ordered enrichment pipeline, and fans the result out to every
 * configured {@link DataSink}.
 *
 * <p>Sink order is deliberate and enforced here rather than assumed:
 * evidence to S3, then the queryable row, then the enriched topic that
 * pages someone. A failure part-way through is retried by the container
 * (see {@link KafkaConsumerConfig}), and every sink is idempotent, so the
 * repeat is harmless - but an alert must never precede the evidence.
 */
@Component
public class KafkaConsumerAdapter {

    private final List<EventProcessor> processors;
    private final List<DataSink> sinks;

    public KafkaConsumerAdapter(List<EventProcessor> processors, List<DataSink> sinks) {
        this.processors = processors.stream()
                .sorted(Comparator.comparingInt(EventProcessor::order))
                .toList();
        // Spring usually injects @Order-annotated beans in order, but sorting
        // here makes the guarantee the adapter's own rather than the
        // container's - and lets a test hand them over shuffled.
        List<DataSink> ordered = new ArrayList<>(sinks);
        AnnotationAwareOrderComparator.sort(ordered);
        this.sinks = List.copyOf(ordered);
    }

    /** Visible for testing: the sinks in the order they are written to. */
    List<DataSink> sinks() {
        return sinks;
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
    }
}
