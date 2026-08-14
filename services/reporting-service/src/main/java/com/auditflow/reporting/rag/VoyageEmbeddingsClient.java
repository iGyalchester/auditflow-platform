package com.auditflow.reporting.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Thin client for the Voyage AI embeddings API. Follows the notifier
 * convention: with no API key configured the client reports itself
 * unconfigured and callers fall back, so no secret is needed to boot or test.
 * The builder is injected so tests can bind a MockRestServiceServer to it.
 */
@Component
public class VoyageEmbeddingsClient {

    private final RestClient restClient;
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public VoyageEmbeddingsClient(RestClient.Builder restClientBuilder,
                                  @Value("${reporting.rag.voyage.api-key:}") String apiKey,
                                  @Value("${reporting.rag.voyage.base-url:https://api.voyageai.com}") String baseUrl,
                                  @Value("${reporting.rag.voyage.model:voyage-3.5-lite}") String model) {
        this.restClient = restClientBuilder.build();
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    /**
     * Embeds the given texts; {@code inputType} is {@code "document"} when
     * indexing the corpus and {@code "query"} when embedding a question.
     * Returned vectors are in the same order as the input texts.
     */
    public List<float[]> embed(List<String> texts, String inputType) {
        if (!isConfigured()) {
            throw new IllegalStateException("Voyage API key is not configured");
        }
        EmbeddingsResponse response = restClient.post()
                .uri(baseUrl + "/v1/embeddings")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("input", texts, "model", model, "input_type", inputType))
                .retrieve()
                .body(EmbeddingsResponse.class);
        if (response == null || response.data() == null) {
            throw new IllegalStateException("Empty response from Voyage embeddings API");
        }
        return response.data().stream()
                .sorted(Comparator.comparingInt(EmbeddingData::index))
                .map(EmbeddingData::toFloats)
                .toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingsResponse(List<EmbeddingData> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingData(List<Double> embedding, int index) {
        float[] toFloats() {
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = embedding.get(i).floatValue();
            }
            return vector;
        }
    }
}
