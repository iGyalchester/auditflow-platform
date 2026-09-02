package com.auditflow.ingestion.adapters;

import com.auditflow.common.model.AuditEvent;
import com.auditflow.common.enums.EventType;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class KafkaProducerAdapterTest {

    private final KafkaTemplate<String, AuditEvent> template = mock(KafkaTemplate.class);
    private final KafkaProducerAdapter adapter = new KafkaProducerAdapter(template, "audit-events", Duration.ofMillis(200));

    private final AuditEvent event = AuditEvent.builder()
            .eventId("evt-1").customerId("cust-1").type(EventType.AUTH_EVENT).timestamp(Instant.now()).build();

    @Test
    void returnsOnceTheBrokerAcknowledges() {
        when(template.send(eq("audit-events"), eq("cust-1"), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        assertThatCode(() -> adapter.publish(event)).doesNotThrowAnyException();
    }

    @Test
    void brokerErrorBecomesPublishFailed() {
        when(template.send(eq("audit-events"), eq("cust-1"), any()))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("no in-sync replicas")));

        assertThatThrownBy(() -> adapter.publish(event))
                .isInstanceOf(PublishFailedException.class)
                .hasMessageContaining("evt-1")
                .hasMessageContaining("no in-sync replicas");
    }

    @Test
    void anAckThatNeverArrivesBecomesPublishFailedWithinTheTimeout() {
        when(template.send(eq("audit-events"), eq("cust-1"), any()))
                .thenReturn(new CompletableFuture<>()); // never completes

        long start = System.nanoTime();
        assertThatThrownBy(() -> adapter.publish(event)).isInstanceOf(PublishFailedException.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThatCode(() -> {
            if (elapsedMs > 2_000) throw new AssertionError("waited " + elapsedMs + "ms, timeout not honoured");
        }).doesNotThrowAnyException();
    }
}
