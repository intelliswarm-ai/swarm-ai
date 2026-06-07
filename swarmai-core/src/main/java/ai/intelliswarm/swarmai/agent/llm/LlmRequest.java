package ai.intelliswarm.swarmai.agent.llm;

import ai.intelliswarm.swarmai.api.PublicApi;
import ai.intelliswarm.swarmai.tool.base.BaseTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Provider-neutral LLM request. {@link LlmClient} implementations translate
 * this to whatever their underlying API expects. Carries the substrate types
 * from Phase 1 ({@link CachePolicy}, {@link ThinkingBudget},
 * {@link CitationConfig}, {@link DocumentBlock}) so callers don't lose the
 * features that are available on native providers.
 *
 * <p>Adapters that don't support a particular substrate feature (e.g.
 * Spring AI's ChatClient doesn't expose prompt caching today) silently ignore
 * the corresponding fields — callers can still set them without a runtime
 * failure when running on a non-Anthropic provider.
 */
@PublicApi(since = "1.0.24")
public final class LlmRequest {

    private final String model;
    private final String system;
    private final List<LlmMessage> messages;
    private final List<BaseTool> tools;
    private final List<DocumentBlock> documents;
    private final int maxOutputTokens;
    private final Double temperature;
    private final CachePolicy cachePolicy;
    private final ThinkingBudget thinkingBudget;
    private final CitationConfig citationConfig;

    private LlmRequest(Builder b) {
        this.model = Objects.requireNonNull(b.model, "model must not be null");
        this.system = b.system;
        if (b.messages.isEmpty()) {
            throw new IllegalArgumentException("at least one message is required");
        }
        this.messages = List.copyOf(b.messages);
        this.tools = List.copyOf(b.tools);
        this.documents = List.copyOf(b.documents);
        this.maxOutputTokens = b.maxOutputTokens;
        this.temperature = b.temperature;
        this.cachePolicy = b.cachePolicy != null ? b.cachePolicy : CachePolicy.disabled();
        this.thinkingBudget = b.thinkingBudget != null ? b.thinkingBudget : ThinkingBudget.DISABLED;
        this.citationConfig = b.citationConfig != null ? b.citationConfig : CitationConfig.DISABLED;
    }

    public String model() { return model; }
    public String system() { return system; }
    public List<LlmMessage> messages() { return messages; }
    public List<BaseTool> tools() { return tools; }
    public List<DocumentBlock> documents() { return documents; }
    public int maxOutputTokens() { return maxOutputTokens; }
    public Double temperature() { return temperature; }
    public CachePolicy cachePolicy() { return cachePolicy; }
    public ThinkingBudget thinkingBudget() { return thinkingBudget; }
    public CitationConfig citationConfig() { return citationConfig; }

    public static Builder builder() { return new Builder(); }

    @PublicApi(since = "1.0.24")
    public static final class Builder {
        private String model;
        private String system;
        private final List<LlmMessage> messages = new ArrayList<>();
        private final List<BaseTool> tools = new ArrayList<>();
        private final List<DocumentBlock> documents = new ArrayList<>();
        private int maxOutputTokens = 4096;
        private Double temperature;
        private CachePolicy cachePolicy;
        private ThinkingBudget thinkingBudget;
        private CitationConfig citationConfig;

        public Builder model(String model) { this.model = model; return this; }
        public Builder system(String system) { this.system = system; return this; }
        public Builder message(LlmMessage m) { this.messages.add(m); return this; }
        public Builder messages(List<LlmMessage> ms) { this.messages.addAll(ms); return this; }
        public Builder tool(BaseTool t) { this.tools.add(t); return this; }
        public Builder tools(List<BaseTool> ts) { this.tools.addAll(ts); return this; }
        public Builder document(DocumentBlock d) { this.documents.add(d); return this; }
        public Builder documents(List<DocumentBlock> ds) { this.documents.addAll(ds); return this; }
        public Builder maxOutputTokens(int n) { this.maxOutputTokens = n; return this; }
        public Builder temperature(Double t) { this.temperature = t; return this; }
        public Builder cachePolicy(CachePolicy p) { this.cachePolicy = p; return this; }
        public Builder thinkingBudget(ThinkingBudget b) { this.thinkingBudget = b; return this; }
        public Builder citationConfig(CitationConfig c) { this.citationConfig = c; return this; }

        public LlmRequest build() { return new LlmRequest(this); }
    }
}
