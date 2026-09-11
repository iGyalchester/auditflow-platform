package com.auditflow.ingestion.security;

import com.auditflow.ingestion.adapters.KafkaProducerAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The filter's behaviour is tested once, in common-lib. What is
 * service-specific is that this service's property prefix reaches it.
 *
 * <p>Ingestion's prefix is audit.ingestion.rate-limit, not
 * audit.rate-limit: bind the wrong one and nothing fails, the limiter just
 * silently runs on defaults, which for this service are 200/s.
 */
@SpringBootTest(properties = {
        "audit.ingestion.rate-limit.burst=1",
        "audit.ingestion.rate-limit.requests-per-second=0.001",
        // There is no broker here and the publisher is mocked, so stop
        // KafkaAdmin spending its retry budget failing to declare the topic.
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.admin.auto-create=false"})
@AutoConfigureMockMvc
class RateLimitConfigTest {

    private static final String BODY = """
            {"eventId":"evt-1","customerId":"cust-1","type":"AUTH_EVENT"}
            """;

    @MockBean
    private KafkaProducerAdapter kafkaProducerAdapter;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theIngestionPrefixBindsToTheSharedFilter() throws Exception {
        mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }
}
