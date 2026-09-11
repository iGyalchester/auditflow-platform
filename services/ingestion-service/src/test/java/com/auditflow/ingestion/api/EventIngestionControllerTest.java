package com.auditflow.ingestion.api;

import com.auditflow.ingestion.adapters.KafkaProducerAdapter;
import com.auditflow.ingestion.adapters.PublishFailedException;
import com.auditflow.ingestion.validation.SchemaValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.auditflow.common.model.AuditEvent;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EventIngestionController.class)
// the real validator rather than a mock of it: it is our own code, and
// mocking it would hide the domain checks this endpoint depends on
@Import(SchemaValidator.class)
class EventIngestionControllerTest {

    private static final String BODY = """
            {"eventId":"evt-1","customerId":"cust-1","type":"AUTH_EVENT","action":"LOGIN_FAILURE"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private KafkaProducerAdapter kafkaProducerAdapter;

    @Test
    void acknowledgedPublishIs202() throws Exception {
        mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted());
    }

    @Test
    void unacknowledgedPublishIs503WithRetryAfterSoTheSourceResends() throws Exception {
        doThrow(new PublishFailedException("evt-1", new RuntimeException("broker down")))
                .when(kafkaProducerAdapter).publish(any());

        mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.error").value("event not durably stored, retry"));
    }

    @Test
    void invalidBodyIs400NotAPublishAttempt() throws Exception {
        mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void oversizedFieldsAre400RatherThanA202ThatCannotBeStored() throws Exception {
        // Without the @Size limits an oversized field was accepted, answered
        // 202, published to Kafka, and only failed at the Aurora insert -
        // after the sender had been told the event was safely stored.
        String body = """
                {"eventId":"%s","customerId":"cust-1","type":"AUTH_EVENT","action":"LOGIN_FAILURE"}
                """.formatted("e".repeat(65));

        mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        verify(kafkaProducerAdapter, never()).publish(any());
    }

    @Test
    void occurredAtIsKeptSoTheSourcesClockDecidesTheEventTime() throws Exception {
        // An hour ago: the backlog case. Arrival time would have put this in
        // the wrong report window and made the outage invisible.
        Instant sourceTime = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        String body = """
                {"eventId":"evt-1","customerId":"cust-1","type":"AUTH_EVENT","occurredAt":"%s"}
                """.formatted(sourceTime);

        mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());

        assertThat(published().getTimestamp()).isEqualTo(sourceTime);
    }

    @Test
    void anAbsentOccurredAtStillDefaultsToArrivalTime() throws Exception {
        Instant before = Instant.now();

        mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted());

        assertThat(published().getTimestamp()).isBetween(before, Instant.now());
    }

    @Test
    void anOccurredAtBeyondTheSkewAllowanceIs400() throws Exception {
        // A broken clock, or a source parking evidence outside the window a
        // report will look at. Either way, not something to store quietly.
        String body = """
                {"eventId":"evt-1","customerId":"cust-1","type":"AUTH_EVENT","occurredAt":"%s"}
                """.formatted(Instant.now().plus(1, ChronoUnit.DAYS));

        mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("occurredAt is in the future"));

        verify(kafkaProducerAdapter, never()).publish(any());
    }

    @Test
    void smallClockSkewIsToleratedRatherThanRejected() throws Exception {
        // Source clocks drift. Refusing a minute of skew would drop real
        // evidence for a reason the sender cannot see or fix.
        Instant slightlyAhead = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
        String body = """
                {"eventId":"evt-1","customerId":"cust-1","type":"AUTH_EVENT","occurredAt":"%s"}
                """.formatted(slightlyAhead);

        mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());

        assertThat(published().getTimestamp()).isEqualTo(slightlyAhead);
    }

    private AuditEvent published() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(kafkaProducerAdapter).publish(captor.capture());
        return captor.getValue();
    }
}