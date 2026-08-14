package com.auditflow.reporting.rag;

import java.util.List;

/**
 * One entry in the RAG corpus: a compliance control as loaded from
 * {@code shared/compliance-controls/*.yaml}. Kept separate from the shared
 * {@code ComplianceControl} model because the YAML's {@code eventTypes} field
 * has no counterpart there, and the corpus needs it as retrieval evidence.
 */
public record ControlDocument(String controlId,
                              String framework,
                              String name,
                              String description,
                              List<String> eventTypes) {

    /** Text sent to the embeddings API and into the generation prompt. */
    public String embeddingText() {
        return "%s control %s (%s): %s Evidence event types: %s"
                .formatted(framework, controlId, name, description, String.join(", ", eventTypes));
    }
}
