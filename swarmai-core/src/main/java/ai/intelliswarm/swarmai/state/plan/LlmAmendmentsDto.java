package ai.intelliswarm.swarmai.state.plan;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The structured shape an LLM is asked to produce when acting as a
 * {@link Replanner}. Spring AI's {@code BeanOutputConverter} reads this type
 * to generate JSON-schema instructions for the prompt and to parse the
 * response back into a record graph.
 *
 * <p>{@link Risk} is encoded as a {@link String} (rather than the
 * {@link Risk} enum) so that LLMs can return slightly malformed values like
 * {@code "low"} or {@code "moderate"} without the converter throwing — the
 * {@link LlmReplanner} normalises them, defaulting to {@link Risk#UNKNOWN}.
 *
 * <p>Field semantics:
 * <ul>
 *   <li>{@link #newEntries} — fresh PROPOSED entries to append. Each carries a
 *       summary, a risk classification, and an optional rationale.</li>
 *   <li>{@link #supersessions} — pairs of an existing entry id with a fresh
 *       proposal that should replace it.</li>
 *   <li>{@link #rationale} — a short free-form note explaining what the
 *       replanner did. Used for audit trails and operator review.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LlmAmendmentsDto(
        List<NewEntry> newEntries,
        List<Supersession> supersessions,
        String rationale) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NewEntry(
            String summary,
            String risk,
            String rationale) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Supersession(
            String oldId,
            String replacementSummary,
            String replacementRisk,
            String rationale) { }
}
