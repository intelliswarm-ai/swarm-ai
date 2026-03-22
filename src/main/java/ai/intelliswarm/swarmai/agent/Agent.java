package ai.intelliswarm.swarmai.agent;

import ai.intelliswarm.swarmai.memory.Memory;
import ai.intelliswarm.swarmai.knowledge.Knowledge;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
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
    private static final int MAX_CONTEXT_LENGTH = 2000;
    private static final int MAX_PROMPT_LENGTH = 100_000; // ~25K tokens, fits in 128K context models

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

            // Safety: truncate prompt if too large for model context window
            if (userPrompt.length() > MAX_PROMPT_LENGTH) {
                logger.warn("Agent [{}] prompt too large ({} chars), truncating to {} chars",
                        role, userPrompt.length(), MAX_PROMPT_LENGTH);
                userPrompt = userPrompt.substring(0, MAX_PROMPT_LENGTH)
                        + "\n\n[... content truncated due to length ...]";
            }

            if (verbose) {
                logger.info("Agent [{}] executing task ({} chars prompt): {}",
                        role, userPrompt.length(), truncate(task.getDescription(), 80));
            }

            // Use Spring AI ChatClient fluent API with system + user messages
            var requestBuilder = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt);

            if (!tools.isEmpty()) {
                requestBuilder.functions(tools.stream()
                    .map(BaseTool::getFunctionName)
                    .toArray(String[]::new));
            }

            // Call LLM with retry and timeout
            int timeoutMs = maxExecutionTime != null ? maxExecutionTime : DEFAULT_TIMEOUT_MS;
            String response = callWithRetry(() -> requestBuilder.call().content(), DEFAULT_MAX_RETRIES, timeoutMs);
            long executionTimeMs = System.currentTimeMillis() - startTime;

            // Save result to memory for future context
            if (memory != null) {
                memory.save(id,
                        "Task: " + truncate(task.getDescription(), 200) +
                        "\nResult: " + truncate(response, 500),
                        Map.of("taskId", task.getId(), "executionTimeMs", executionTimeMs));
            }

            if (verbose) {
                logger.info("Agent [{}] completed task in {} ms ({} chars output)",
                        role, executionTimeMs, response != null ? response.length() : 0);
            }

            return TaskOutput.builder()
                .agentId(id)
                .taskId(task.getId())
                .rawOutput(response)
                .description(task.getDescription())
                .summary(extractSummary(response))
                .executionTimeMs(executionTimeMs)
                .build();

        } catch (Exception e) {
            long executionTimeMs = System.currentTimeMillis() - startTime;
            logger.error("Agent [{}] failed task after {} ms: {}", role, executionTimeMs, e.getMessage());
            throw new RuntimeException("Failed to execute task: " + task.getId(), e);
        }
    }

    private String callWithRetry(Supplier<String> llmCall, int maxRetries, int timeoutMs) {
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                CompletableFuture<String> future = CompletableFuture.supplyAsync(llmCall::get);
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
        if (!tools.isEmpty()) {
            system.append("\nYou have access to the following tools: ");
            tools.forEach(tool -> system.append(tool.getFunctionName()).append(" (")
                    .append(tool.getDescription()).append("), "));
            system.setLength(system.length() - 2); // remove trailing ", "
            system.append("\n");
        }
        return system.toString();
    }

    private String buildUserPrompt(Task task, List<TaskOutput> context) {
        StringBuilder prompt = new StringBuilder();

        // Add context from previous tasks — use full output, not truncated summary
        if (context != null && !context.isEmpty()) {
            prompt.append("Context from previous tasks:\n");
            for (TaskOutput ctx : context) {
                prompt.append("--- ").append(ctx.getDescription() != null ? ctx.getDescription() : "Previous task").append(" ---\n");
                String output = ctx.getRawOutput();
                if (output != null) {
                    prompt.append(truncate(output, MAX_CONTEXT_LENGTH)).append("\n");
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