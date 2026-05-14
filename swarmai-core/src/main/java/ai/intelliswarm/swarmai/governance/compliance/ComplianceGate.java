package ai.intelliswarm.swarmai.governance.compliance;

import ai.intelliswarm.swarmai.agent.llm.InlineCitation;
import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.List;

/**
 * Output-level compliance check. Evaluated after an agent produces a final
 * answer and before that answer is delivered to a downstream system or human.
 *
 * <p>Gates compose: a workflow plugs in {@link CitationRequiredGate} +
 * {@link SuspectNumberGate} + custom gates, then runs the output through each;
 * {@link ComplianceReport} aggregates the findings.
 *
 * <p>For lifecycle-level gates (delegation depth, single-writer invariant)
 * that fire mid-flight rather than at output time, see
 * {@link DelegationDepthPolicy} and {@link SingleWriterInvariantEnforcer}.
 */
@PublicApi(since = "1.0.24")
public interface ComplianceGate {

    /** Stable identifier — also the code prefix for findings this gate emits. */
    String name();

    /** Evaluate the output. Returns an empty list when nothing to report. */
    List<ComplianceFinding> evaluate(Output output);

    /**
     * Output context handed to a gate. Carries both the text and the inline
     * citations the LLM emitted so a gate can reason about source-anchoring.
     *
     * @param text       generated text being evaluated
     * @param citations  inline citations attached to the output (may be empty)
     * @param metadata   workflow/agent attributes for routing decisions
     */
    @PublicApi(since = "1.0.24")
    record Output(String text, List<InlineCitation> citations, java.util.Map<String, Object> metadata) {
        public Output {
            text = text == null ? "" : text;
            citations = citations == null ? List.of() : List.copyOf(citations);
            metadata = metadata == null ? java.util.Map.of() : java.util.Map.copyOf(metadata);
        }

        public static Output of(String text, List<InlineCitation> citations) {
            return new Output(text, citations, java.util.Map.of());
        }
    }
}
