package ai.intelliswarm.swarmai.llm.anthropic;

import ai.intelliswarm.swarmai.agent.llm.CachePolicy;
import ai.intelliswarm.swarmai.agent.llm.CitationConfig;
import ai.intelliswarm.swarmai.agent.llm.DocumentBlock;
import ai.intelliswarm.swarmai.agent.llm.ThinkingBudget;
import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A request to the native Anthropic adapter. Built with {@link Builder} so
 * Phase-1 features (caching, thinking, citations, documents) can be opted into
 * independently.
 */
@PublicApi(since = "1.0.24")
public final class AnthropicRequest {

    private final String model;
    private final String system;
    private final List<AnthropicMessage> messages;
    private final List<AnthropicToolSpec> tools;
    private final List<DocumentBlock> documents;
    private final List<ServerToolSpec> serverTools;
    private final int maxOutputTokens;
    private final Double temperature;
    private final CachePolicy cachePolicy;
    private final ThinkingBudget thinkingBudget;
    private final CitationConfig citationConfig;
    private final ToolChoice toolChoice;
    private final boolean disableParallelToolUse;
    private final List<String> betas;

    private AnthropicRequest(Builder b) {
        this.model = Objects.requireNonNull(b.model, "model must not be null");
        this.system = b.system;
        if (b.messages.isEmpty()) {
            throw new IllegalArgumentException("at least one message is required");
        }
        this.messages = List.copyOf(b.messages);
        this.tools = List.copyOf(b.tools);
        this.documents = List.copyOf(b.documents);
        this.serverTools = List.copyOf(b.serverTools);
        this.maxOutputTokens = b.maxOutputTokens;
        this.temperature = b.temperature;
        this.cachePolicy = b.cachePolicy != null ? b.cachePolicy : CachePolicy.disabled();
        this.thinkingBudget = b.thinkingBudget != null ? b.thinkingBudget : ThinkingBudget.DISABLED;
        this.citationConfig = b.citationConfig != null ? b.citationConfig : CitationConfig.DISABLED;
        this.toolChoice = b.toolChoice != null ? b.toolChoice : ToolChoice.AUTO;
        this.disableParallelToolUse = b.disableParallelToolUse;
        this.betas = List.copyOf(b.betas);
    }

    public String model() { return model; }
    public String system() { return system; }
    public List<AnthropicMessage> messages() { return messages; }
    public List<AnthropicToolSpec> tools() { return tools; }
    public List<DocumentBlock> documents() { return documents; }
    public List<ServerToolSpec> serverTools() { return serverTools; }
    public int maxOutputTokens() { return maxOutputTokens; }
    public Double temperature() { return temperature; }
    public CachePolicy cachePolicy() { return cachePolicy; }
    public ThinkingBudget thinkingBudget() { return thinkingBudget; }
    public CitationConfig citationConfig() { return citationConfig; }
    public ToolChoice toolChoice() { return toolChoice; }
    public boolean disableParallelToolUse() { return disableParallelToolUse; }
    public List<String> betas() { return betas; }

    public static Builder builder() { return new Builder(); }

    public enum ToolChoice { AUTO, ANY, NONE }

    public static final class Builder {
        private String model;
        private String system;
        private final List<AnthropicMessage> messages = new ArrayList<>();
        private final List<AnthropicToolSpec> tools = new ArrayList<>();
        private final List<DocumentBlock> documents = new ArrayList<>();
        private final List<ServerToolSpec> serverTools = new ArrayList<>();
        private int maxOutputTokens = 4096;
        private Double temperature;
        private CachePolicy cachePolicy;
        private ThinkingBudget thinkingBudget;
        private CitationConfig citationConfig;
        private ToolChoice toolChoice;
        private boolean disableParallelToolUse;
        private final List<String> betas = new ArrayList<>();

        public Builder model(String model) { this.model = model; return this; }
        public Builder system(String system) { this.system = system; return this; }
        public Builder message(AnthropicMessage m) { this.messages.add(m); return this; }
        public Builder messages(List<AnthropicMessage> ms) { this.messages.addAll(ms); return this; }
        public Builder tool(AnthropicToolSpec t) { this.tools.add(t); return this; }
        public Builder tools(List<AnthropicToolSpec> ts) { this.tools.addAll(ts); return this; }
        public Builder document(DocumentBlock d) { this.documents.add(d); return this; }
        public Builder documents(List<DocumentBlock> ds) { this.documents.addAll(ds); return this; }
        public Builder serverTool(ServerToolSpec spec) { this.serverTools.add(ServerToolSpec.requireNonNull(spec)); return this; }
        public Builder serverTools(List<ServerToolSpec> specs) { specs.forEach(this::serverTool); return this; }
        public Builder maxOutputTokens(int n) { this.maxOutputTokens = n; return this; }
        public Builder temperature(Double t) { this.temperature = t; return this; }
        public Builder cachePolicy(CachePolicy p) { this.cachePolicy = p; return this; }
        public Builder thinkingBudget(ThinkingBudget b) { this.thinkingBudget = b; return this; }
        public Builder citationConfig(CitationConfig c) { this.citationConfig = c; return this; }
        public Builder toolChoice(ToolChoice c) { this.toolChoice = c; return this; }
        public Builder disableParallelToolUse(boolean v) { this.disableParallelToolUse = v; return this; }
        public Builder beta(String beta) { this.betas.add(beta); return this; }

        public AnthropicRequest build() { return new AnthropicRequest(this); }
    }
}
