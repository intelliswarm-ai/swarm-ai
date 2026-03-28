package ai.intelliswarm.swarmai.agent;

import ai.intelliswarm.swarmai.config.ModelContextConfig;
import ai.intelliswarm.swarmai.memory.Memory;
import ai.intelliswarm.swarmai.knowledge.Knowledge;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.skill.GeneratedSkill;
import ai.intelliswarm.swarmai.tool.mcp.McpToolAdapter;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public class Agent {

    private static final Logger logger = LoggerFactory.getLogger(Agent.class);
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final int DEFAULT_TIMEOUT_MS = 300_000; // 5 minutes

    @NotNull
    private final String id;
    
    @NotBlank
    private final String role;
    
    @NotBlank
    private final String goal;
    
    @NotBlank
    private final String backstory;
    
    private final List<BaseTool> tools;
    private final boolean verbose;
    private final boolean allowDelegation;
    private final Integer maxIter;
    private final Integer maxRpm;
    private final Double temperature;
    private final Integer maxExecutionTime;
    private final boolean systemTemplate;
    private final boolean stepCallback;
    
    @JsonIgnore
    private ChatClient chatClient;

    @JsonIgnore
    private Memory memory;

    @JsonIgnore
    private Knowledge knowledge;

    private final String modelName;

    @JsonIgnore
    private final ModelContextConfig contextConfig;
    
    private final LocalDateTime createdAt;
    private final Map<String, Object> metadata;
    private Integer executionCount = 0;

    private Agent(Builder builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID().toString();
        this.role = builder.role;
        this.goal = builder.goal;
        this.backstory = builder.backstory;
        this.tools = new ArrayList<>(builder.tools);
        this.verbose = builder.verbose;
        this.allowDelegation = builder.allowDelegation;
        this.maxIter = builder.maxIter;
        this.maxRpm = builder.maxRpm;
        this.temperature = builder.temperature;
        this.maxExecutionTime = builder.maxExecutionTime;
        this.systemTemplate = builder.systemTemplate;
        this.stepCallback = builder.stepCallback;
        this.chatClient = builder.chatClient;
        this.memory = builder.memory;
        this.knowledge = builder.knowledge;
        this.modelName = builder.modelName;
        this.contextConfig = ModelContextConfig.forModel(builder.modelName);
        this.createdAt = LocalDateTime.now();
        this.metadata = new HashMap<>(builder.metadata);
    }

    public CompletableFuture<TaskOutput> executeTaskAsync(Task task, List<TaskOutput> context) {
        return CompletableFuture.supplyAsync(() -> executeTask(task, context));
    }

    public TaskOutput executeTask(Task task, List<TaskOutput> context) {
        incrementExecutionCount();
        long startTime = System.currentTimeMillis();

        try {
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(task, context);

            // Dynamic context management: truncate prompt based on model's context window
            int maxPromptChars = contextConfig.getMaxTotalPromptChars() - systemPrompt.length();
            if (userPrompt.length() > maxPromptChars) {
                logger.warn("Agent [{}] prompt too large ({} chars), truncating to {} chars (model: {}, context: {} tokens)",
                        role, userPrompt.length(), maxPromptChars,
                        modelName != null ? modelName : "default",
                        contextConfig.getContextWindowTokens());
                userPrompt = userPrompt.substring(0, maxPromptChars)
                        + "\n\n[... content truncated to fit model context window ...]";
            }

            if (verbose) {
                logger.info("Agent [{}] executing task ({} chars prompt, {} token context): {}",
                        role, userPrompt.length(), contextConfig.getContextWindowTokens(),
                        truncate(task.getDescription(), 80));
            }

            // Use Spring AI ChatClient fluent API with system + user messages
            var requestBuilder = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt);

            // Override model per-agent if specified (otherwise uses Spring default)
            if (modelName != null && !modelName.isBlank()) {
                requestBuilder.options(org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .model(modelName)
                        .build());
            }

            if (!tools.isEmpty()) {
                // Split tools: Spring bean tools use toolNames(), dynamic tools use toolCallbacks()
                List<String> springToolNames = new ArrayList<>();
                List<BaseTool> dynamicTools = new ArrayList<>();

                for (BaseTool tool : tools) {
                    if (tool instanceof McpToolAdapter || tool instanceof GeneratedSkill) {
                        // Dynamic tools (MCP, GeneratedSkill) must be registered as callbacks
                        dynamicTools.add(tool);
                    } else {
                        springToolNames.add(tool.getFunctionName());
                    }
                }

                if (!springToolNames.isEmpty()) {
                    requestBuilder.toolNames(springToolNames.toArray(new String[0]));
                }

                if (!dynamicTools.isEmpty()) {
                    List<org.springframework.ai.tool.ToolCallback> callbacks = new ArrayList<>();

                    for (BaseTool tool : dynamicTools) {
                        if (tool instanceof McpToolAdapter) {
                            // MCP tools use the fixed McpToolInput record
                            callbacks.add(org.springframework.ai.tool.function.FunctionToolCallback
                                    .builder(tool.getFunctionName(),
                                            (java.util.function.Function<McpToolInput, String>)
                                                    input -> {
                                                Map<String, Object> params = new HashMap<>();
                                                if (input.url() != null) params.put("url", input.url());
                                                if (input.query() != null) params.put("query", input.query());
                                                if (input.input() != null) params.put("input", input.input());
                                                return String.valueOf(tool.execute(params));
                                            })
                                    .description(tool.getDescription())
                                    .inputType(McpToolInput.class)
                                    .build());
                        } else {
                            // GeneratedSkill and other dynamic tools — DynamicToolInput captures all params
                            callbacks.add(org.springframework.ai.tool.function.FunctionToolCallback
                                    .builder(tool.getFunctionName(),
                                            (java.util.function.Function<DynamicToolInput, String>)
                                                    input -> {
                                                Map<String, Object> params = new HashMap<>(input.getParams());
                                                return String.valueOf(tool.execute(params));
                                            })
                                    .description(tool.getDescription())
                                    .inputType(DynamicToolInput.class)
                                    .build());
                        }
                    }

                    requestBuilder.toolCallbacks(callbacks.toArray(new org.springframework.ai.tool.ToolCallback[0]));
                }
            }

            // Call LLM with retry and timeout — capture full ChatResponse for token stats
            int timeoutMs = maxExecutionTime != null ? maxExecutionTime : DEFAULT_TIMEOUT_MS;
            ChatResponse chatResponse = callWithRetry(
                    () -> requestBuilder.call().chatResponse(), DEFAULT_MAX_RETRIES, timeoutMs);

            String response = chatResponse.getResult().getOutput().getText();
            long executionTimeMs = System.currentTimeMillis() - startTime;

            // Extract token usage (API returns Long in some versions, Integer in others)
            Long promptTokens = null, completionTokens = null, totalTokens = null;
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                var usage = chatResponse.getMetadata().getUsage();
                promptTokens = toLong(usage.getPromptTokens());
                completionTokens = toLong(usage.getCompletionTokens());
                totalTokens = toLong(usage.getTotalTokens());
            }

            // Save result to memory for future context
            if (memory != null) {
                memory.save(id,
                        "Task: " + truncate(task.getDescription(), 200) +
                        "\nResult: " + truncate(response, 500),
                        Map.of("taskId", task.getId(), "executionTimeMs", executionTimeMs));
            }

            if (verbose) {
                logger.info("Agent [{}] completed task in {} ms ({} chars, {} prompt tokens, {} completion tokens)",
                        role, executionTimeMs,
                        response != null ? response.length() : 0,
                        promptTokens != null ? promptTokens : "N/A",
                        completionTokens != null ? completionTokens : "N/A");
            }

            return TaskOutput.builder()
                .agentId(id)
                .taskId(task.getId())
                .rawOutput(response)
                .description(task.getDescription())
                .summary(extractSummary(response))
                .executionTimeMs(executionTimeMs)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                .build();

        } catch (Exception e) {
            long executionTimeMs = System.currentTimeMillis() - startTime;
            logger.error("Agent [{}] failed task after {} ms: {}", role, executionTimeMs, e.getMessage());
            throw new RuntimeException("Failed to execute task: " + task.getId(), e);
        }
    }

    private <T> T callWithRetry(Supplier<T> llmCall, int maxRetries, int timeoutMs) {
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                CompletableFuture<T> future = CompletableFuture.supplyAsync(llmCall::get);
                return future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new RuntimeException("LLM call timed out after " + timeoutMs + "ms", e);
            } catch (Exception e) {
                lastException = e;
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                String msg = cause.getMessage() != null ? cause.getMessage() : "";
                // Don't retry non-transient errors (bad request, auth, context length)
                if (msg.contains("400") || msg.contains("401") || msg.contains("403")
                        || msg.contains("context_length_exceeded") || msg.contains("NonTransient")) {
                    throw new RuntimeException("LLM call failed (non-retryable): " + msg, e);
                }
                if (attempt < maxRetries) {
                    long backoffMs = (long) Math.pow(2, attempt) * 1000;
                    logger.warn("Agent [{}] LLM call attempt {}/{} failed: {}. Retrying in {} ms...",
                            role, attempt, maxRetries, cause.getMessage(), backoffMs);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry backoff", ie);
                    }
                }
            }
        }
        throw new RuntimeException("LLM call failed after " + maxRetries + " attempts", lastException);
    }

    private String buildSystemPrompt() {
        StringBuilder system = new StringBuilder();
        system.append("You are ").append(role).append(".\n");
        system.append("Your goal is: ").append(goal).append("\n");
        system.append("Your backstory: ").append(backstory).append("\n");

        // Date awareness
        system.append("\nToday's date is: ").append(java.time.LocalDate.now()).append("\n");

        if (!tools.isEmpty()) {
            system.append("\nYou have access to the following tools. " +
                "You MUST actively call these tools to gather real data — do NOT answer from general knowledge alone. " +
                "Call the appropriate tool function for every step that requires external data or action.\n\n");
            for (BaseTool tool : tools) {
                system.append("- **").append(tool.getFunctionName()).append("**");
                system.append(" [").append(tool.getCategory()).append("]");
                system.append(": ").append(tool.getDescription()).append("\n");
                if (tool.getTriggerWhen() != null) {
                    system.append("  USE WHEN: ").append(tool.getTriggerWhen()).append("\n");
                }
                if (tool.getAvoidWhen() != null) {
                    system.append("  AVOID WHEN: ").append(tool.getAvoidWhen()).append("\n");
                }
                if (!tool.getTags().isEmpty()) {
                    system.append("  Tags: ").append(String.join(", ", tool.getTags())).append("\n");
                }
            }
            system.append("\nIMPORTANT: Always call the tool functions above to gather data. " +
                "Never produce a final answer without first calling at least one tool.\n");
        }

        // Anti-hallucination guardrails
        system.append("\nCRITICAL RULES YOU MUST FOLLOW:\n");
        system.append("1. NEVER fabricate data, statistics, scores, or company information. " +
                "If you don't have data, explicitly state: \"DATA NOT AVAILABLE\" or \"NO INFORMATION FOUND\".\n");
        system.append("2. If a tool search returns no useful results, report that the search found nothing " +
                "rather than inventing information. Say: \"Search returned no relevant results for [topic]\".\n");
        system.append("3. Distinguish between facts you are confident about and estimates/opinions. " +
                "Mark facts as [CONFIRMED] and estimates as [ESTIMATE].\n");
        system.append("4. If the topic you are asked about does not exist or you have no knowledge of it, " +
                "say so clearly: \"No public information found about [topic]. This may be a pre-announcement, " +
                "internal codename, or misspelling.\"\n");
        system.append("5. Use today's date for any timelines or recommendations. Do not reference past dates " +
                "as future actions.\n");
        system.append("6. SELF-ADJUSTMENT: If a tool call fails, times out, or returns an error, " +
                "you MUST adapt your approach immediately. Try a narrower scope, different parameters, " +
                "or an alternative tool. For example: if scanning an entire subnet times out, scan " +
                "individual hosts; if a full port scan times out, use --top-ports 100.\n");
        system.append("7. OUTPUT FILES: When saving scan results or output files, ALWAYS write them to " +
                "/app/output/ directory (e.g., /app/output/nmap_results.txt). This directory persists " +
                "outside the container. Never write to the current directory or /tmp.\n");

        return system.toString();
    }

    private String buildUserPrompt(Task task, List<TaskOutput> context) {
        StringBuilder prompt = new StringBuilder();

        // Dynamic context budget based on model's context window
        int contextBudget = contextConfig.getMaxPriorContextChars();

        // Add context from previous tasks — budget shared across all prior outputs
        if (context != null && !context.isEmpty()) {
            int perTaskBudget = Math.max(500, contextBudget / context.size());
            prompt.append("Context from previous tasks:\n");
            for (TaskOutput ctx : context) {
                prompt.append("--- ").append(ctx.getDescription() != null ? ctx.getDescription() : "Previous task").append(" ---\n");
                String output = ctx.getRawOutput();
                if (output != null) {
                    prompt.append(truncate(output, perTaskBudget)).append("\n");
                }
                prompt.append("\n");
            }
        }

        // Add memory context
        if (memory != null) {
            List<String> relevantMemories = memory.search(task.getDescription(), 5);
            if (relevantMemories != null && !relevantMemories.isEmpty()) {
                prompt.append("Relevant memories from previous executions:\n");
                for (String mem : relevantMemories) {
                    prompt.append("- ").append(mem).append("\n");
                }
                prompt.append("\n");
            }
        }

        // Add knowledge context
        if (knowledge != null) {
            String knowledgeContext = knowledge.query(task.getDescription());
            if (StringUtils.hasText(knowledgeContext)) {
                prompt.append("Relevant knowledge:\n").append(knowledgeContext).append("\n\n");
            }
        }

        // Add task description and expected output
        prompt.append("Task: ").append(task.getDescription()).append("\n");
        if (StringUtils.hasText(task.getExpectedOutput())) {
            prompt.append("Expected Output: ").append(task.getExpectedOutput()).append("\n");
        }

        return prompt.toString();
    }

    private String extractSummary(String response) {
        if (response == null || response.length() <= 200) {
            return response;
        }
        return response.substring(0, 197) + "...";
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }

    /**
     * Generic input record for MCP tools. Covers common tool parameter patterns.
     * Spring AI will generate a proper JSON schema from this record's fields.
     */
    public record McpToolInput(
            @com.fasterxml.jackson.annotation.JsonProperty("url") String url,
            @com.fasterxml.jackson.annotation.JsonProperty("query") String query,
            @com.fasterxml.jackson.annotation.JsonProperty("input") String input
    ) {}

    /**
     * Generic input class for dynamically generated skills.
     * Uses @JsonAnySetter to capture ALL parameters the LLM sends,
     * regardless of field name — no need to enumerate every possible field.
     */
    public static class DynamicToolInput {
        private final Map<String, String> params = new HashMap<>();

        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void set(String key, Object value) {
            params.put(key, value != null ? value.toString() : null);
        }

        public Map<String, String> getParams() { return params; }
    }

    private String buildToolInputSchema(BaseTool tool) {
        // Try to get schema from the tool itself
        Map<String, Object> schema = tool.getParameterSchema();
        if (schema != null && !schema.isEmpty()) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(schema);
            } catch (Exception e) {
                logger.debug("Failed to serialize tool schema, using default");
            }
        }
        // Default schema based on tool name — common MCP tools
        String toolName = tool.getFunctionName();
        return switch (toolName) {
            case "fetch" -> """
                {"type":"object","properties":{"url":{"type":"string","description":"URL to fetch"},"max_length":{"type":"integer","description":"Maximum content length","default":5000},"raw":{"type":"boolean","description":"Return raw content without conversion","default":false}},"required":["url"]}""";
            case "brave_search" -> """
                {"type":"object","properties":{"query":{"type":"string","description":"Search query"},"count":{"type":"integer","description":"Number of results","default":5}},"required":["query"]}""";
            default -> """
                {"type":"object","properties":{"input":{"type":"string","description":"Input for the tool"}},"required":["input"]}""";
        };
    }

    private static Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Long l) return l;
        if (value instanceof Number n) return n.longValue();
        return null;
    }

    private synchronized void incrementExecutionCount() {
        this.executionCount++;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String role;
        private String goal;
        private String backstory;
        private List<BaseTool> tools = new ArrayList<>();
        private boolean verbose = false;
        private boolean allowDelegation = false;
        private Integer maxIter;
        private Integer maxRpm;
        private Double temperature;
        private Integer maxExecutionTime;
        private boolean systemTemplate = false;
        private boolean stepCallback = false;
        private ChatClient chatClient;
        private Memory memory;
        private Knowledge knowledge;
        private String modelName;
        private Map<String, Object> metadata = new HashMap<>();

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder role(String role) {
            this.role = role;
            return this;
        }

        public Builder goal(String goal) {
            this.goal = goal;
            return this;
        }

        public Builder backstory(String backstory) {
            this.backstory = backstory;
            return this;
        }

        public Builder tools(List<BaseTool> tools) {
            this.tools = new ArrayList<>(tools);
            return this;
        }

        public Builder tool(BaseTool tool) {
            this.tools.add(tool);
            return this;
        }

        public Builder verbose(boolean verbose) {
            this.verbose = verbose;
            return this;
        }

        public Builder allowDelegation(boolean allowDelegation) {
            this.allowDelegation = allowDelegation;
            return this;
        }

        public Builder maxIter(Integer maxIter) {
            this.maxIter = maxIter;
            return this;
        }

        public Builder maxRpm(Integer maxRpm) {
            this.maxRpm = maxRpm;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxExecutionTime(Integer maxExecutionTime) {
            this.maxExecutionTime = maxExecutionTime;
            return this;
        }

        public Builder systemTemplate(boolean systemTemplate) {
            this.systemTemplate = systemTemplate;
            return this;
        }

        public Builder stepCallback(boolean stepCallback) {
            this.stepCallback = stepCallback;
            return this;
        }

        public Builder chatClient(ChatClient chatClient) {
            this.chatClient = chatClient;
            return this;
        }

        public Builder memory(Memory memory) {
            this.memory = memory;
            return this;
        }

        public Builder knowledge(Knowledge knowledge) {
            this.knowledge = knowledge;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder metadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        public Agent build() {
            Objects.requireNonNull(role, "Role cannot be null");
            Objects.requireNonNull(goal, "Goal cannot be null");
            Objects.requireNonNull(backstory, "Backstory cannot be null");
            Objects.requireNonNull(chatClient, "ChatClient cannot be null");
            
            return new Agent(this);
        }
    }

    // Getters
    public String getId() { return id; }
    public String getRole() { return role; }
    public String getGoal() { return goal; }
    public String getBackstory() { return backstory; }
    public List<BaseTool> getTools() { return new ArrayList<>(tools); }
    public boolean isVerbose() { return verbose; }
    public boolean isAllowDelegation() { return allowDelegation; }
    public Integer getMaxIter() { return maxIter; }
    public Integer getMaxRpm() { return maxRpm; }
    public Double getTemperature() { return temperature; }
    public Integer getMaxExecutionTime() { return maxExecutionTime; }
    public boolean isSystemTemplate() { return systemTemplate; }
    public boolean isStepCallback() { return stepCallback; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public Map<String, Object> getMetadata() { return new HashMap<>(metadata); }
    public Integer getExecutionCount() { return executionCount; }
    public String getModelName() { return modelName; }
    public ModelContextConfig getContextConfig() { return contextConfig; }
    public ChatClient getChatClient() { return chatClient; }
    public Memory getMemory() { return memory; }
    public Knowledge getKnowledge() { return knowledge; }

    /**
     * Create a copy of this agent with additional tools added to its toolkit.
     * Used by SelfImprovingProcess to expand agent capabilities at runtime.
     */
    public Agent withAdditionalTools(List<BaseTool> newTools) {
        List<BaseTool> allTools = new ArrayList<>(this.tools);
        allTools.addAll(newTools);
        Agent copy = Agent.builder()
            .role(this.role)
            .goal(this.goal)
            .backstory(this.backstory)
            .chatClient(this.chatClient)
            .tools(allTools)
            .verbose(this.verbose)
            .allowDelegation(this.allowDelegation)
            .maxRpm(this.maxRpm != null ? this.maxRpm : 0)
            .temperature(this.temperature != null ? this.temperature : 0.7)
            .modelName(this.modelName)
            .build();
        copy.memory = this.memory;
        copy.knowledge = this.knowledge;
        return copy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Agent)) return false;
        Agent agent = (Agent) o;
        return Objects.equals(id, agent.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Agent{" +
                "id='" + id + '\'' +
                ", role='" + role + '\'' +
                ", goal='" + goal + '\'' +
                '}';
    }
}