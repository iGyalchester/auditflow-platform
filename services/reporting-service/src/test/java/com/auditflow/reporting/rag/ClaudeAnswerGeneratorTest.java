package com.auditflow.reporting.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline-only: the live Claude call is an external boundary exercised by
 * running the service with an API key, not by tests.
 */
class ClaudeAnswerGeneratorTest {

    @Test
    void isConfiguredFalseWhenKeyBlank() {
        assertThat(new ClaudeAnswerGenerator("", "claude-opus-5").isConfigured()).isFalse();
        assertThat(new ClaudeAnswerGenerator("sk-test", "claude-opus-5").isConfigured()).isTrue();
    }

    @Test
    void buildPromptIncludesQuestionAndControlIds() {
        ControlDocument doc = new ControlDocument("AC-2", "SOC2", "Account Management",
                "Accounts are managed.", List.of("AUTH_EVENT"));

        String prompt = ClaudeAnswerGenerator.buildPrompt("Who manages accounts?",
                List.of(new ControlVectorStore.ScoredControl(doc, 0.9)));

        assertThat(prompt)
                .contains("Who manages accounts?")
                .contains("AC-2")
                .contains("Account Management");
    }
}
