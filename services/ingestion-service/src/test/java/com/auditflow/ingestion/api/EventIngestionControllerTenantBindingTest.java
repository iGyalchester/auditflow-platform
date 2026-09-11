package com.auditflow.ingestion.api;

import com.auditflow.ingestion.adapters.KafkaProducerAdapter;
import com.auditflow.ingestion.security.IngestTokenFilter;
import com.auditflow.ingestion.validation.SchemaValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole point of binding a token to a tenant: a source authenticated as
 * one customer cannot write events as another. The filter and the real
 * SchemaValidator are both in the slice, so this exercises the actual chain
 * rather than a stubbed decision.
 */
@WebMvcTest(EventIngestionController.class)
@Import({IngestTokenFilter.class, SchemaValidator.class})
@TestPropertySource(properties = "audit.ingestion.tokens=cust-1=tok-1,other=tok-2")
class EventIngestionControllerTenantBindingTest {

    private static final String AS_CUST_1 = """
            {"eventId":"evt-1","customerId":"cust-1","type":"AUTH_EVENT","action":"LOGIN_FAILURE"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private KafkaProducerAdapter kafkaProducerAdapter;

    @Test
    void theTokensOwnCustomerIsAccepted() throws Exception {
        mockMvc.perform(post("/api/v1/events")
                        .header(IngestTokenFilter.TOKEN_HEADER, "tok-1")
                        .contentType(MediaType.APPLICATION_JSON).content(AS_CUST_1))
                .andExpect(status().isAccepted());

        verify(kafkaProducerAdapter).publish(any());
    }

    @Test
    void anotherCustomersEventIs403AndNeverReachesKafka() throws Exception {
        mockMvc.perform(post("/api/v1/events")
                        .header(IngestTokenFilter.TOKEN_HEADER, "tok-2")
                        .contentType(MediaType.APPLICATION_JSON).content(AS_CUST_1))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(
                        "customerId does not match the tenant bound to the presented token"));

        // the forgery must not be published, not even to be dropped later
        verify(kafkaProducerAdapter, never()).publish(any());
    }

    @Test
    void noTokenIs401() throws Exception {
        mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON).content(AS_CUST_1))
                .andExpect(status().isUnauthorized());

        verify(kafkaProducerAdapter, never()).publish(any());
    }
}
