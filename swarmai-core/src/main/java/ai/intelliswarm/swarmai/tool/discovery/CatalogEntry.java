package ai.intelliswarm.swarmai.tool.discovery;

import java.util.List;
import java.util.Objects;

/**
 * A single entry in a {@link ToolCatalog} — describes one tool the harness
 * <em>could</em> expose to an agent without committing to load its schema
 * up-front.
 *
 * <p>This is the load-bearing record for deferred tool loading: when an agent
 * starts a conversation, the system prompt only carries the {@code name} and
 * {@code description} of every catalogued tool (not the full JSON schema).
 * The agent uses {@link ToolSearchService} to find relevant tools by query,
 * inspects {@code schemaSummary} to understand their inputs, and only then
 * does the harness actually wire the tool's callback into the next call.
 *
 * @param name           tool identifier matching {@code [a-z0-9_]+} (Spring AI
 *                       function-call grammar). Never blank.
 * @param description    one-line description shown in search results and
 *                       loaded into the system prompt as part of the tool
 *                       discovery surface
 * @param keywords       additional search terms — synonyms or category tags
 *                       not present in the description (e.g. {@code "math"} on
 *                       a {@code calculator} tool). May be empty.
 * @param schemaSummary  human-readable summary of the tool's argument shape,
 *                       e.g. {@code "args: expression (string)"}. Returned
 *                       inline in search results so the agent can plan a call
 *                       without a schema-fetch round-trip. May be empty.
 */
public record CatalogEntry(
        String name,
        String description,
        List<String> keywords,
        String schemaSummary) {

    public CatalogEntry {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        keywords = (keywords == null) ? List.of() : List.copyOf(keywords);
        schemaSummary = (schemaSummary == null) ? "" : schemaSummary;
    }

    /** Convenience: entry with no keywords or schema summary. */
    public static CatalogEntry of(String name, String description) {
        return new CatalogEntry(name, description, List.of(), "");
    }

    /** Convenience: entry with keywords but no schema summary. */
    public static CatalogEntry of(String name, String description, List<String> keywords) {
        return new CatalogEntry(name, description, keywords, "");
    }
}
