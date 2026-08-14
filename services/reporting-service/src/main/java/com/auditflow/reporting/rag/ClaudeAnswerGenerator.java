package com.auditflow.reporting.rag;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Generates grounded answers with Claude via the official Anthropic SDK.
 * Falls back per the notifier convention: with no API key configured,
 * {@link #isConfigured()} is false and the endpoint answers with retrieved
 * controls only, so no secret is needed to boot or test. The SDK client is
 * built lazily on first use for the same reason.
 */
@Component
public class ClaudeAnswerGenerator implements AnswerGenerator {

    private static final String SYSTEM_PROMPT =
            "You are AuditFlow's compliance assistant. Answer ONLY from the provided "
            + "compliance controls. Cite controlIds (e.g. AC-2) for every claim. If the "
            + "controls do not cover the question, say so.";

    private final String apiKey;
    private final String model;
    private volatile AnthropicClient client;

    public ClaudeAnswerGenerator(@Value("${reporting.rag.anthropic.api-key:}") String apiKey,
                                 @Value("${reporting.rag.anthropic.model:claude-opus-5}") String model) {
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    @Override
    public String generate(String question, List<ControlVectorStore.ScoredControl> context) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(1024L)
                .system(SYSTEM_PROMPT)
                .addUserMessage(buildPrompt(question, context))
                .build();
        Message response = client().messages().create(params);
        if (response.stopReason().map(StopReason.REFUSAL::equals).orElse(false)) {
            return "The model declined to answer this question.";
        }
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> text.text())
                .collect(Collectors.joining("\n"));
    }

    static String buildPrompt(String question, List<ControlVectorStore.ScoredControl> context) {
        StringBuilder prompt = new StringBuilder("Compliance controls:\n");
        int i = 1;
        for (ControlVectorStore.ScoredControl scored : context) {
            prompt.append(i++).append(". ").append(scored.document().embeddingText()).append('\n');
        }
        prompt.append("\nQuestion: ").append(question);
        return prompt.toString();
    }

    private AnthropicClient client() {
        AnthropicClient current = client;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (client == null) {
                client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
            }
            return client;
        }
    }
}
