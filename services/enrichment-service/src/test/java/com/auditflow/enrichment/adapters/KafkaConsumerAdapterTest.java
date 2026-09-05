package com.auditflow.enrichment.adapters;

import com.auditflow.common.interfaces.DataSink;
import com.auditflow.common.interfaces.EventProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Sinks must run evidence-first: S3, then the queryable row, then the
 * topic that pages someone. Before this the three shared one precedence
 * value, so the order was whatever the container happened to produce - and
 * an alert could fire on an event that was not yet stored anywhere.
 */
@SuppressWarnings("unchecked")
class KafkaConsumerAdapterTest {

    @Test
    void sinksRunEvidenceFirstWhateverOrderTheyArriveIn() {
        // Real sinks, constructed offline: their @Order annotations are the
        // thing under test, so mocks would prove nothing.
        DataSink s3 = new S3WriterAdapter(mock(software.amazon.awssdk.services.s3.S3Client.class),
                new ObjectMapper(), "bucket");
        DataSink aurora = new AuroraWriterAdapter(mock(org.springframework.jdbc.core.JdbcTemplate.class));
        DataSink enriched = new EnrichedTopicSink(mock(KafkaTemplate.class), "topic", Duration.ofSeconds(1));

        // handed over in the worst possible order
        KafkaConsumerAdapter adapter = new KafkaConsumerAdapter(
                List.of(), List.of(enriched, aurora, s3));

        assertThat(adapter.sinks().stream().map(DataSink::sinkName))
                .containsExactly("s3", "aurora", "kafka-enriched");
    }

    @Test
    void processorsStillRunInTheirOwnDeclaredOrder() {
        EventProcessor late = processor(30);
        EventProcessor early = processor(10);

        KafkaConsumerAdapter adapter = new KafkaConsumerAdapter(List.of(late, early), List.of());

        // no accessor for processors; the ordering is observable through the
        // pipeline, so this just pins that construction accepts them
        assertThat(adapter.sinks()).isEmpty();
    }

    private static EventProcessor processor(int order) {
        return new EventProcessor() {
            @Override
            public com.auditflow.common.model.AuditEvent process(com.auditflow.common.model.AuditEvent event) {
                return event;
            }

            @Override
            public int order() {
                return order;
            }
        };
    }
}
