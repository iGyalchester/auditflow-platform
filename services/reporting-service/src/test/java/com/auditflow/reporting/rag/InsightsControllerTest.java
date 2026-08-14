package com.auditflow.reporting.rag;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Controller constructed directly, per the repo's plain-JUnit convention. The
 * LLM boundary is faked via the AnswerGenerator interface; retrieval runs the
 * real store in keyword-fallback mode (no keys, no network).
 */
class InsightsControllerTest {

    private final ReportingControlsCatalog fixtureCatalog =
            new ReportingControlsCatalog("classpath*:test-controls/*.yaml");

    private ControlVectorStore keywordStore() {
        VoyageEmbeddingsClient unconfigured =
                new VoyageEmbeddingsClient(RestClient.builder(), "", "http://voyage.test", "voyage-3.5-lite");
        return new ControlVectorStore(fixtureCatalog, unconfigured);
    }

    private record FakeGenerator(boolean configured, String answer, RuntimeException failure,
                                 List<List<ControlVectorStore.ScoredControl>> receivedContexts)
            implements AnswerGenerator {

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public String generate(String question, List<ControlVectorStore.ScoredControl> context) {
            receivedContexts.add(context);
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    @Test
    void returnsKeywordSourcesAndFallbackAnswerWhenNothingConfigured() {
        InsightsController controller = new InsightsController(keywordStore(),
                new ClaudeAnswerGenerator("", "claude-opus-5"));

        InsightsController.InsightsResponse response =
                controller.ask("Which controls cover exports of customer data?", 4);

        assertThat(response.generated()).isFalse();
        assertThat(response.retrievalMode()).isEqualTo("KEYWORD");
        assertThat(response.sources()).isNotEmpty();
        assertThat(response.sources().get(0).controlId()).isEqualTo("EXPORT-1");
        assertThat(response.answer()).contains("not configured").contains("EXPORT-1");
    }

    @Test
    void usesGeneratorWhenConfigured() {
        FakeGenerator generator = new FakeGenerator(true, "Grounded answer citing EXPORT-1.",
                null, new ArrayList<>());
        InsightsController controller = new InsightsController(keywordStore(), generator);

        InsightsController.InsightsResponse response =
                controller.ask("Which controls cover exports of customer data?", 4);

        assertThat(response.generated()).isTrue();
        assertThat(response.answer()).isEqualTo("Grounded answer citing EXPORT-1.");
        assertThat(generator.receivedContexts()).hasSize(1);
        assertThat(generator.receivedContexts().get(0))
                .anyMatch(scored -> scored.document().controlId().equals("EXPORT-1"));
    }

    @Test
    void rejectsBlankQuestion() {
        InsightsController controller = new InsightsController(keywordStore(),
                new ClaudeAnswerGenerator("", "claude-opus-5"));

        assertThatThrownBy(() -> controller.ask("   ", 4))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void fallsBackWhenGeneratorThrows() {
        FakeGenerator generator = new FakeGenerator(true, null,
                new RuntimeException("boom"), new ArrayList<>());
        InsightsController controller = new InsightsController(keywordStore(), generator);

        InsightsController.InsightsResponse response =
                controller.ask("Which controls cover exports of customer data?", 4);

        assertThat(response.generated()).isFalse();
        assertThat(response.answer()).contains("generation failed");
        assertThat(response.sources()).isNotEmpty();
    }
}
