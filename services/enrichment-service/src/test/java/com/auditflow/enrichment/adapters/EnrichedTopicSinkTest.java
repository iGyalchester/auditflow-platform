package com.auditflow.enrichment.adapters;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.model.AuditEvent;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class EnrichedTopicSinkTest {

    private final KafkaTemplate<String, AuditEvent> template = mock(KafkaTemplate.class);
    private final EnrichedTopicSink sink = new EnrichedTopicSink(template, "audit-events-enriched", Duration.ofMillis(200));
    private final AuditEvent event = AuditEvent.builder()
            .eventId("evt-9").customerId("cust-1").type(EventType.AUTH_EVENT)
            .riskLevel(RiskLevel.HIGH).timestamp(Instant.now()).build();

    @Test
    void publishesKeyedByCustomerAndReturnsOnAck() {
        when(template.send(eq("audit-events-enriched"), eq("cust-1"), same(event)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        assertThatCode(() -> sink.write(event)).doesNotThrowAnyException();
    }

    @Test
    void unacknowledgedSendThrowsSoTheListenerRetries() {
        when(template.send(eq("audit-events-enriched"), eq("cust-1"), same(event)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not enough replicas")));

        assertThatThrownBy(() -> sink.write(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("evt-9");
    }
}
