package com.auditflow.reporting.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG endpoint: retrieves the compliance controls most relevant to a
 * natural-language question and has Claude answer grounded in them. Degrades
 * instead of failing - retrieval falls back to keyword ranking without a
 * Voyage key, and without an Anthropic key (or on a generation error) the
 * response carries the retrieved controls with {@code generated=false}.
 */
@RestController
@RequestMapping("/api/v1/reports/insights")
public class InsightsController {

    private static final Logger log = LoggerFactory.getLogger(InsightsController.class);

    public record SourceRef(String controlId, String framework, String name, double score) {
    }

    public record InsightsResponse(String question, String retrievalMode, boolean generated,
                                   String answer, List<SourceRef> sources) {
    }

    private final ControlVectorStore vectorStore;
    private final AnswerGenerator answerGenerator;

    public InsightsController(ControlVectorStore vectorStore, AnswerGenerator answerGenerator) {
        this.vectorStore = vectorStore;
        this.answerGenerator = answerGenerator;
    }

    @GetMapping
    public InsightsResponse ask(@RequestParam("question") String question,
                                @RequestParam(name = "topK", defaultValue = "4") int topK) {
        if (question.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question must not be blank");
        }

        ControlVectorStore.RetrievalResult result = vectorStore.search(question, topK);

        String answer;
        boolean generated = false;
        if (answerGenerator.isConfigured()) {
            try {
                answer = answerGenerator.generate(question, result.controls());
                generated = true;
            } catch (RuntimeException e) {
                log.warn("[rag] answer generation failed - returning retrieved controls only", e);
                answer = "Answer generation failed; showing retrieved controls only.";
            }
        } else {
            log.info("[rag:fallback] no Anthropic API key configured - returning retrieved controls only");
            answer = "RAG generation is not configured (set ANTHROPIC_API_KEY). Most relevant controls: "
                    + result.controls().stream()
                            .map(scored -> scored.document().controlId())
                            .collect(Collectors.joining(", "));
        }

        List<SourceRef> sources = result.controls().stream()
                .map(scored -> new SourceRef(
                        scored.document().controlId(),
                        scored.document().framework(),
                        scored.document().name(),
                        Math.round(scored.score() * 10_000d) / 10_000d))
                .toList();
        return new InsightsResponse(question, result.mode().name(), generated, answer, sources);
    }
}
