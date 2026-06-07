package ai.intelliswarm.swarmai.tool.reasoning;

import ai.intelliswarm.swarmai.api.PublicApi;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Mixture-of-Agents tool — runs N reference models in parallel on the same
 * prompt, then has an aggregator model synthesise a final answer from all of
 * them. <em>Not voting</em>: the aggregator reads every response and produces
 * a single synthesis that's typically better than any individual answer.
 *
 * <p>Decoupled from any specific LLM provider — caller supplies the inference
 * functions via {@link Reference} and {@link #aggregator}. The tool itself is
 * provider-agnostic, so you can mix Claude + GPT + Gemini for the reference
 * set and use whichever for the synthesis pass.
 *
 * <p>Default temperatures (overridable per reference): references at 0.6 for
 * diversity, aggregator at 0.4 for synthesis fidelity. These match the
 * commonly published recipe.
 *
 * <p>Input shape:
 * <pre>{@code
 * {"prompt": "Analyse ACME's competitive position in industrials"}
 * }</pre>
 *
 * <p>Output: the aggregator's synthesised answer plus the raw reference
 * responses (useful for transparency / audit).
 */
@PublicApi(since = "1.0.24")
public final class MixtureOfAgentsTool implements BaseTool {

    private final List<Reference> references;
    private final Function<AggregatorInput, String> aggregator;
    private final ExecutorService executor;

    public MixtureOfAgentsTool(List<Reference> references, Function<AggregatorInput, String> aggregator) {
        this(references, aggregator, Executors.newFixedThreadPool(
                Math.max(2, Objects.requireNonNull(references, "references must not be null").size())));
    }

    public MixtureOfAgentsTool(List<Reference> references,
                               Function<AggregatorInput, String> aggregator,
                               ExecutorService executor) {
        Objects.requireNonNull(references, "references must not be null");
        Objects.requireNonNull(aggregator, "aggregator must not be null");
        Objects.requireNonNull(executor, "executor must not be null");
        if (references.isEmpty()) {
            throw new IllegalArgumentException("at least one reference is required");
        }
        this.references = List.copyOf(references);
        this.aggregator = aggregator;
        this.executor = executor;
    }

    @Override public String getFunctionName() { return "mixture_of_agents"; }

    @Override
    public String getDescription() {
        return "Run multiple LLMs in parallel on the same prompt, then have an aggregator "
                + "synthesise a single answer. Use for hard reasoning tasks where you want the "
                + "consensus across models (IC memos, scientific synthesis, ambiguous coding decisions).";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.READ_ONLY; }
    @Override public boolean isAsync() { return false; }
    @Override public String getCategory() { return "reasoning"; }
    @Override public List<String> getTags() { return List.of("reasoning", "synthesis", "consensus", "moa"); }

    @Override public String getTriggerWhen() {
        return "User asks for synthesis across multiple model perspectives, hard reasoning, "
                + "or wants a consensus answer (financial memo synthesis, scientific review, "
                + "ambiguous design decisions)";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "prompt", Map.of("type", "string",
                                "description", "The question or task to send to every reference model")),
                "required", List.of("prompt"));
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        Object promptObj = parameters.get("prompt");
        if (!(promptObj instanceof String prompt) || prompt.isBlank()) {
            return Map.of("ok", false, "error", "Missing or invalid 'prompt'");
        }

        // Fan out to every reference in parallel, collecting (label, response) pairs.
        List<CompletableFuture<Map.Entry<String, String>>> futures = new ArrayList<>();
        for (Reference ref : references) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    String response = ref.runner().apply(prompt);
                    return Map.entry(ref.label(), response);
                } catch (RuntimeException e) {
                    return Map.entry(ref.label(), "[ERROR] " + e.getMessage());
                }
            }, executor));
        }

        List<Map.Entry<String, String>> referenceResponses = new ArrayList<>();
        for (CompletableFuture<Map.Entry<String, String>> f : futures) {
            try {
                referenceResponses.add(f.join());
            } catch (Exception e) {
                referenceResponses.add(Map.entry("unknown", "[ERROR] " + e.getMessage()));
            }
        }

        // Aggregator gets the prompt + every reference response.
        String synthesis;
        try {
            synthesis = aggregator.apply(new AggregatorInput(prompt, referenceResponses));
        } catch (RuntimeException e) {
            return Map.of("ok", false, "error", "Aggregator failed: " + e.getMessage());
        }

        List<Map<String, String>> referencesOut = new ArrayList<>();
        for (var entry : referenceResponses) {
            Map<String, String> r = new LinkedHashMap<>();
            r.put("label", entry.getKey());
            r.put("response", entry.getValue());
            referencesOut.add(r);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("synthesis", synthesis);
        out.put("references", referencesOut);
        out.put("referenceCount", references.size());
        return out;
    }

    /**
     * One reference model. {@code label} is what the aggregator sees in front
     * of the response. {@code runner} is the inference function — typically a
     * small lambda calling {@code anthropicClient.send(...)} or another
     * provider.
     */
    @PublicApi(since = "1.0.24")
    public record Reference(String label, Function<String, String> runner) {
        public Reference {
            Objects.requireNonNull(label, "label must not be null");
            Objects.requireNonNull(runner, "runner must not be null");
        }
    }

    /** Payload handed to the aggregator function. */
    @PublicApi(since = "1.0.24")
    public record AggregatorInput(String prompt, List<Map.Entry<String, String>> referenceResponses) {}
}
