package com.auditflow.reporting.rag;

import java.util.List;

/**
 * Boundary to the answer-generating LLM. An interface for the same reason the
 * notifiers have one: the implementation talks to an external service, and
 * tests fake the boundary rather than mocking internals.
 */
public interface AnswerGenerator {

    boolean isConfigured();

    /** Returns an answer grounded in the given controls, citing their controlIds. */
    String generate(String question, List<ControlVectorStore.ScoredControl> context);
}
