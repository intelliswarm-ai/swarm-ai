package ai.intelliswarm.swarmai.knowledge.rag;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.List;

/**
 * Result of {@link RagPipeline#query(String)}.
 *
 * @param question  the question that was asked
 * @param answer    the synthesised reply, with inline {@code [source: ...]} tags
 * @param citations parsed citations (sourceId / chunk index / passage / optional page)
 * @param refused   {@code true} if the model said "passages do not answer" instead of guessing
 * @param durationMs end-to-end latency including retrieval + synthesis
 */
@PublicApi(since = "1.0.9")
public record RagAnswer(
        String question,
        String answer,
        List<Citation> citations,
        boolean refused,
        long durationMs
) {}
