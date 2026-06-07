package ai.intelliswarm.swarmai.knowledge.rag;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * Prompt strings used by {@link RagPipeline}. Made public so callers can
 * compose their own prompts on top of the defaults.
 *
 * <p>The system prompt is intentionally simple. The 2026-04-26 IntelliDoc eval
 * showed that small models (qwen2.5:3b, llama3.2:3b) over-refuse when given
 * enumerated multi-rule prompts — refusal rate dropped from 36% → 12% after
 * switching from a 6-rule prompt to this 5-bullet one, modeled on
 * langchain's RAG default.
 */
@PublicApi(since = "1.0.9")
public final class RagPrompts {

    private RagPrompts() {}

    /** Default system prompt — minimal rules, anti-hallucination via citation tag. */
    public static final String SYSTEM = """
            Answer the question based EXCLUSIVELY on the source passages provided in the user message.

            Rules:
            - Write the actual answer in plain English. Include the factual content, not just a citation marker.
            - Append the citation tag immediately after each fact. Example: "The capital of Japan is Tokyo [source: japan.md #0]."
            - The citation tag must use the EXACT label shown in each passage's header.
            - If the passages contain a formula, equation, or specific notation, copy it verbatim.
            - If the passages don't answer the question, say so plainly — don't guess.
            - Be direct and concise.
            """;

    /**
     * Build the user prompt: passages + question. Passages should already have
     * the {@code [source: filename #N]} headers so the model can cite them.
     */
    public static String userPrompt(String passages, String question) {
        return "Source passages:\n" + passages + "\n\nQuestion: " + question;
    }

    /** Phrase the synthesizer must emit when no passage answers the question. */
    public static final String REFUSAL = "The provided passages do not answer this question.";
}
