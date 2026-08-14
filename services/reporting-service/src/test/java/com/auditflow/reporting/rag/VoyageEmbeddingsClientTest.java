package com.auditflow.reporting.rag;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Fakes the Voyage HTTP boundary with MockRestServiceServer, same pattern as
 * the gateway proxy tests.
 */
class VoyageEmbeddingsClientTest {

    private static final String BASE_URL = "http://voyage.test";

    private RestClient.Builder builder = RestClient.builder();

    private VoyageEmbeddingsClient client(String apiKey) {
        return new VoyageEmbeddingsClient(builder, apiKey, BASE_URL, "voyage-3.5-lite");
    }

    @Test
    void postsInputsWithBearerAuthAndModel() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        VoyageEmbeddingsClient client = client("test-key");

        server.expect(requestTo(BASE_URL + "/v1/embeddings"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("voyage-3.5-lite"))
                .andExpect(jsonPath("$.input_type").value("document"))
                .andExpect(jsonPath("$.input[0]").value("first text"))
                .andRespond(withSuccess("""
                        {"data": [{"embedding": [0.1, 0.2], "index": 0}]}
                        """, MediaType.APPLICATION_JSON));

        List<float[]> vectors = client.embed(List.of("first text"), "document");

        assertThat(vectors).hasSize(1);
        assertThat(vectors.get(0)).containsExactly(0.1f, 0.2f);
        server.verify();
    }

    @Test
    void returnsEmbeddingsInInputOrder() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        VoyageEmbeddingsClient client = client("test-key");

        server.expect(requestTo(BASE_URL + "/v1/embeddings"))
                .andRespond(withSuccess("""
                        {"data": [
                          {"embedding": [2.0], "index": 1},
                          {"embedding": [1.0], "index": 0}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        List<float[]> vectors = client.embed(List.of("a", "b"), "query");

        assertThat(vectors.get(0)).containsExactly(1.0f);
        assertThat(vectors.get(1)).containsExactly(2.0f);
    }

    @Test
    void isConfiguredFalseWhenKeyBlank() {
        assertThat(client("").isConfigured()).isFalse();
        assertThat(client("test-key").isConfigured()).isTrue();
    }
}
