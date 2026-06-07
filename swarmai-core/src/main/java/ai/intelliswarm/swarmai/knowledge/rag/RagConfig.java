package ai.intelliswarm.swarmai.knowledge.rag;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * Tunable knobs for {@link RagPipeline}. Defaults are the values that won the
 * 2026-04-26 IntelliDoc RAG iteration battery (see
 * {@code intellidoc/docs/internal/EVAL_2026_04_26_FULL_ITERATIONS.md}):
 * <ul>
 *   <li>{@code chunkSize=800, chunkOverlap=100} — peer-aligned, beat 500/150
 *       on faithfulness and latency.
 *   <li>{@code topK=5} — K=10 added prompt tokens without proportional recall.
 *   <li>{@code hybridRetrieval=true} — BM25+vector→RRF caught exact identifiers
 *       (algorithm names, formulas) the embedding model misranks. Disabling
 *       it tanked chunk-hit from 14% to 8% in the eval.
 *   <li>{@code maxPassageChars=2400} — model attention drops past the first
 *       few KB; cutting from 4000 saved ~30% latency.
 *   <li>{@code contextualPrefix=true} — Anthropic-style {@code [filename] }
 *       prepend before embedding (small recall win).
 *   <li>{@code mmrRerank=false} — diversity rerank pushed top-K *off* the
 *       right doc when the answer was concentrated in one section.
 * </ul>
 *
 * Override only what you need:
 * <pre>{@code
 * RagConfig.defaults().withTopK(8).withHybridRetrieval(false);
 * }</pre>
 */
@PublicApi(since = "1.0.9")
public record RagConfig(
        int chunkSize,
        int chunkOverlap,
        int topK,
        int maxPassageChars,
        boolean hybridRetrieval,
        boolean contextualPrefix,
        boolean mmrRerank,
        double temperature,
        int numPredict
) {
    public static RagConfig defaults() {
        return new RagConfig(
                /* chunkSize       */ 800,
                /* chunkOverlap    */ 100,
                /* topK            */ 5,
                /* maxPassageChars */ 2400,
                /* hybridRetrieval */ true,
                /* contextualPrefix*/ true,
                /* mmrRerank       */ false,
                /* temperature     */ 0.2,
                /* numPredict      */ 350
        );
    }

    public RagConfig withChunkSize(int v)        { return new RagConfig(v, chunkOverlap, topK, maxPassageChars, hybridRetrieval, contextualPrefix, mmrRerank, temperature, numPredict); }
    public RagConfig withChunkOverlap(int v)     { return new RagConfig(chunkSize, v, topK, maxPassageChars, hybridRetrieval, contextualPrefix, mmrRerank, temperature, numPredict); }
    public RagConfig withTopK(int v)             { return new RagConfig(chunkSize, chunkOverlap, v, maxPassageChars, hybridRetrieval, contextualPrefix, mmrRerank, temperature, numPredict); }
    public RagConfig withMaxPassageChars(int v)  { return new RagConfig(chunkSize, chunkOverlap, topK, v, hybridRetrieval, contextualPrefix, mmrRerank, temperature, numPredict); }
    public RagConfig withHybridRetrieval(boolean v) { return new RagConfig(chunkSize, chunkOverlap, topK, maxPassageChars, v, contextualPrefix, mmrRerank, temperature, numPredict); }
    public RagConfig withContextualPrefix(boolean v) { return new RagConfig(chunkSize, chunkOverlap, topK, maxPassageChars, hybridRetrieval, v, mmrRerank, temperature, numPredict); }
    public RagConfig withMmrRerank(boolean v)    { return new RagConfig(chunkSize, chunkOverlap, topK, maxPassageChars, hybridRetrieval, contextualPrefix, v, temperature, numPredict); }
    public RagConfig withTemperature(double v)   { return new RagConfig(chunkSize, chunkOverlap, topK, maxPassageChars, hybridRetrieval, contextualPrefix, mmrRerank, v, numPredict); }
    public RagConfig withNumPredict(int v)       { return new RagConfig(chunkSize, chunkOverlap, topK, maxPassageChars, hybridRetrieval, contextualPrefix, mmrRerank, temperature, v); }
}
