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

    public KafkaConsumerAdapter(List<EventProcessor> processors, List<DataSink> sinks) {
        this.processors = processors.stream()
                .sorted(Comparator.comparingInt(EventProcessor::order))
                .toList();
        this.sinks = sinks;
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
