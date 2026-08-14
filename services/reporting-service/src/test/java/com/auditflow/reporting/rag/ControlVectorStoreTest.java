package com.auditflow.reporting.rag;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ControlVectorStoreTest {

    private static final String BASE_URL = "http://voyage.test";

    /** Three known documents (EXPORT-1, AUTH-1, PERM-1) for deterministic ranking. */
    private final ReportingControlsCatalog fixtureCatalog =
            new ReportingControlsCatalog("classpath*:test-controls/*.yaml");

    private final RestClient.Builder builder = RestClient.builder();

    private VoyageEmbeddingsClient voyageClient(String apiKey) {
        return new VoyageEmbeddingsClient(builder, apiKey, BASE_URL, "voyage-3.5-lite");
    }

    @Test
    void cosineSimilarityOfIdenticalVectorsIsOne() {
        float[] v = {0.3f, 0.4f, 0.5f};
        assertThat(ControlVectorStore.cosineSimilarity(v, v)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void cosineSimilarityOfOrthogonalVectorsIsZero() {
        assertThat(ControlVectorStore.cosineSimilarity(new float[]{1, 0}, new float[]{0, 1}))
                .isCloseTo(0.0, within(1e-9));
    }

    @Test
    void keywordSearchRanksDataExportControlFirst() {
        List<ControlVectorStore.ScoredControl> results = ControlVectorStore.keywordSearch(
                fixtureCatalog.documents(), "Which controls cover exports of customer data?", 4);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).document().controlId()).isEqualTo("EXPORT-1");
    }

    @Test
    void keywordSearchHonorsTopKAndDropsZeroScores() {
        List<ControlVectorStore.ScoredControl> results = ControlVectorStore.keywordSearch(
                fixtureCatalog.documents(), "permission privilege data login", 1);

        assertThat(results).hasSize(1);
        assertThat(ControlVectorStore.keywordSearch(
                fixtureCatalog.documents(), "zzz qqq xxx", 4)).isEmpty();
    }

    @Test
    void fallsBackToKeywordWhenVoyageUnconfigured() {
        ControlVectorStore store = new ControlVectorStore(fixtureCatalog, voyageClient(""));

        ControlVectorStore.RetrievalResult result = store.search("exports of customer data", 4);

        assertThat(result.mode()).isEqualTo(ControlVectorStore.RetrievalMode.KEYWORD);
        assertThat(result.controls().get(0).document().controlId()).isEqualTo("EXPORT-1");
    }

    @Test
    void usesSemanticRankingWhenVoyageConfigured() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ControlVectorStore store = new ControlVectorStore(fixtureCatalog, voyageClient("test-key"));

        // First call embeds the 3-document corpus; second embeds the question.
        server.expect(requestTo(BASE_URL + "/v1/embeddings"))
                .andExpect(jsonPath("$.input_type").value("document"))
                .andRespond(withSuccess("""
                        {"data": [
                          {"embedding": [1.0, 0.0], "index": 0},
                          {"embedding": [0.0, 1.0], "index": 1},
                          {"embedding": [0.7, 0.7], "index": 2}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/v1/embeddings"))
                .andExpect(jsonPath("$.input_type").value("query"))
                .andRespond(withSuccess("""
                        {"data": [{"embedding": [0.0, 1.0], "index": 0}]}
                        """, MediaType.APPLICATION_JSON));

        ControlVectorStore.RetrievalResult result = store.search("who logged in?", 2);

        assertThat(result.mode()).isEqualTo(ControlVectorStore.RetrievalMode.SEMANTIC);
        assertThat(result.controls()).hasSize(2);
        // AUTH-1 (index 1) matches the query vector exactly; PERM-1 (index 2) is next.
        assertThat(result.controls().get(0).document().controlId()).isEqualTo("AUTH-1");
        assertThat(result.controls().get(0).score()).isCloseTo(1.0, within(1e-6));
        assertThat(result.controls().get(1).document().controlId()).isEqualTo("PERM-1");
        server.verify();
    }

    @Test
    void fallsBackToKeywordWhenVoyageErrors() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ControlVectorStore store = new ControlVectorStore(fixtureCatalog, voyageClient("test-key"));

        server.expect(requestTo(BASE_URL + "/v1/embeddings")).andRespond(withServerError());

        ControlVectorStore.RetrievalResult result = store.search("exports of customer data", 4);

        assertThat(result.mode()).isEqualTo(ControlVectorStore.RetrievalMode.KEYWORD);
        assertThat(result.controls().get(0).document().controlId()).isEqualTo("EXPORT-1");
        server.verify();
    }
}
