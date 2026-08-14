package com.auditflow.reporting.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * In-memory vector store over the compliance controls corpus. The corpus is a
 * dozen documents, so plain cosine similarity over a list is all the
 * infrastructure this needs. The embedding index is built lazily on first
 * search - never at startup - so a missing key, bad key, or unreachable
 * network can degrade retrieval but can never fail boot. When embeddings are
 * unavailable the store falls back to keyword-overlap ranking, mirroring the
 * notifier fallback convention.
 */
@Component
public class ControlVectorStore {

    private static final Logger log = LoggerFactory.getLogger(ControlVectorStore.class);

    public enum RetrievalMode { SEMANTIC, KEYWORD }

    public record ScoredControl(ControlDocument document, double score) {
    }

    public record RetrievalResult(RetrievalMode mode, List<ScoredControl> controls) {
    }

    private final List<ControlDocument> documents;
    private final VoyageEmbeddingsClient embeddings;
    private volatile List<float[]> index;

    public ControlVectorStore(ReportingControlsCatalog catalog, VoyageEmbeddingsClient embeddings) {
        this.documents = catalog.documents();
        this.embeddings = embeddings;
    }

    public RetrievalResult search(String question, int topK) {
        if (embeddings.isConfigured()) {
            try {
                List<float[]> vectors = ensureIndexed();
                float[] queryVector = embeddings.embed(List.of(question), "query").get(0);
                List<ScoredControl> scored = new ArrayList<>();
                for (int i = 0; i < documents.size(); i++) {
                    scored.add(new ScoredControl(documents.get(i), cosineSimilarity(queryVector, vectors.get(i))));
                }
                scored.sort(Comparator.comparingDouble(ScoredControl::score).reversed());
                return new RetrievalResult(RetrievalMode.SEMANTIC, List.copyOf(scored.subList(0, Math.min(topK, scored.size()))));
            } catch (RestClientException e) {
                log.info("[rag:fallback] Voyage embeddings unavailable - using keyword retrieval", e);
            }
        } else {
            log.info("[rag:fallback] no Voyage API key configured - using keyword retrieval");
        }
        return new RetrievalResult(RetrievalMode.KEYWORD, keywordSearch(documents, question, topK));
    }

    /**
     * Embeds the corpus once, on first use. A failed build is not cached -
     * the next request retries, so a transient outage does not permanently
     * downgrade retrieval to keyword mode.
     */
    private List<float[]> ensureIndexed() {
        List<float[]> current = index;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (index == null) {
                index = embeddings.embed(documents.stream().map(ControlDocument::embeddingText).toList(), "document");
            }
            return index;
        }
    }

    static double cosineSimilarity(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** Ranks by how many question tokens (length > 2) appear in the document text. */
    static List<ScoredControl> keywordSearch(List<ControlDocument> docs, String question, int topK) {
        List<String> tokens = List.of(question.toLowerCase(Locale.ROOT).split("\\W+")).stream()
                .filter(t -> t.length() > 2)
                .toList();
        return docs.stream()
                .map(doc -> {
                    String text = doc.embeddingText().toLowerCase(Locale.ROOT);
                    long score = tokens.stream().filter(text::contains).count();
                    return new ScoredControl(doc, score);
                })
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredControl::score).reversed())
                .limit(topK)
                .toList();
    }
}
