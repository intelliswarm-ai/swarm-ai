package ai.intelliswarm.swarmai.agent;

import ai.intelliswarm.swarmai.memory.Memory;
import ai.intelliswarm.swarmai.knowledge.Knowledge;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class Agent {

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
        
        try {
            String prompt = buildPrompt(task, context);
            
            // Use Spring AI ChatClient fluent API
            var requestBuilder = chatClient.prompt().user(prompt);
            
            if (!tools.isEmpty()) {
                requestBuilder.functions(tools.stream()
                    .map(tool -> tool.getFunctionName())
                    .toArray(String[]::new));
            }
            
            String response = requestBuilder.call().content();
            
            return TaskOutput.builder()
                .agentId(id)
                .taskId(task.getId())
                .rawOutput(response)
                .description(task.getDescription())
                .summary(extractSummary(response))
                .build();
                
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute task: " + task.getId(), e);
        }
    }

    private String buildPrompt(Task task, List<TaskOutput> context) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("You are ").append(role).append(".\n");
        prompt.append("Your goal is: ").append(goal).append("\n");
        prompt.append("Your backstory: ").append(backstory).append("\n\n");
        
        if (!context.isEmpty()) {
            prompt.append("Context from previous tasks:\n");
            context.forEach(ctx -> {
                prompt.append("- ").append(ctx.getSummary()).append("\n");
            });
            prompt.append("\n");
        }
        
        if (knowledge != null) {
            String knowledgeContext = knowledge.query(task.getDescription());
            if (StringUtils.hasText(knowledgeContext)) {
                prompt.append("Relevant knowledge:\n").append(knowledgeContext).append("\n\n");
            }
        }
        
        prompt.append("Task: ").append(task.getDescription()).append("\n");
        
        if (StringUtils.hasText(task.getExpectedOutput())) {
            prompt.append("Expected Output: ").append(task.getExpectedOutput()).append("\n");
        }
        
        return prompt.toString();
    }

    private String extractSummary(String response) {
        if (response.length() <= 100) {
            return response;
        }
        return response.substring(0, 97) + "...";
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