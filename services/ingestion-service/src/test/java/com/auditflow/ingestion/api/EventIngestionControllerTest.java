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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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
}
