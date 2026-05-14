package ai.intelliswarm.swarmai.agent.llm;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.Objects;

/**
 * An LLM-emitted citation anchoring a specific span of model output to a span
 * inside a source document. Distinct from
 * {@link ai.intelliswarm.swarmai.knowledge.rag.Citation} — RAG citations are
 * retrieval-time pointers to chunks; {@code InlineCitation} is what the model
 * itself emits when {@link CitationConfig#enabled()} is true and a document
 * block was attached.
 *
 * <p>Compliance use: in financial workflows every quantitative claim must carry
 * an {@code InlineCitation} or the {@code CitationRequiredGate} will reject the
 * output as draft work product unfit for downstream consumption.
 *
 * @param documentId   stable identifier of the cited source document
 * @param documentTitle optional human-readable title (may be {@code null})
 * @param location     where in that document the cited text lives
 * @param citedText    the exact source text supporting the claim
 */
@PublicApi(since = "1.0.24")
public record InlineCitation(
        String documentId,
        String documentTitle,
        CitationLocation location,
        String citedText) {

    public InlineCitation {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(location, "location must not be null");
        Objects.requireNonNull(citedText, "citedText must not be null");
    }
}
