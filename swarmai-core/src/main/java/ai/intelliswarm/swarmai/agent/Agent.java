package ai.intelliswarm.swarmai.agent;

import ai.intelliswarm.swarmai.api.PublicApi;
import ai.intelliswarm.swarmai.config.ModelContextConfig;
import ai.intelliswarm.swarmai.memory.Memory;
import ai.intelliswarm.swarmai.knowledge.Knowledge;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import ai.intelliswarm.swarmai.tool.base.ToolHook;
import ai.intelliswarm.swarmai.tool.base.ToolHookContext;
import ai.intelliswarm.swarmai.tool.base.ToolHookResult;
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

import ai.intelliswarm.swarmai.exception.AgentExecutionException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@PublicApi(since = "1.0")
public class Agent {

    private static final Logger logger = LoggerFactory.getLogger(Agent.class);
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final int DEFAULT_TIMEOUT_MS = 300_000; // 5 minutes — used as cold-start fallback

    /**
     * Shared latency tracker across all Agent instances. Adapts timeouts based on
     * observed LLM responsiveness (P95 × safetyMultiplier, clamped to a floor/ceiling).
     * Keyed by model name so fast and slow models don't pollute each other's history.
     */
    private static final ai.intelliswarm.swarmai.agent.resilience.LlmLatencyTracker LATENCY_TRACKER =
            new ai.intelliswarm.swarmai.agent.resilience.LlmLatencyTracker();

    /** Accessor for the shared latency tracker — used by LlmHealthChecker.benchmarkAndSeed(). */
    public static ai.intelliswarm.swarmai.agent.resilience.LlmLatencyTracker getLatencyTracker() {
        return LATENCY_TRACKER;
    }
    private static final int DEFAULT_MAX_TURNS = 10; // reactive loop safety cap

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

    private final Integer maxTurns;
    private final PermissionLevel permissionMode;
    private final List<ToolHook> toolHooks;
    private final CompactionConfig compactionConfig;

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
        this.maxTurns = builder.maxTurns;
        this.permissionMode = builder.permissionMode;
        this.toolHooks = new ArrayList<>(builder.toolHooks);
        this.compactionConfig = builder.compactionConfig != null
                ? builder.compactionConfig
                : CompactionConfig.forModel(this.contextConfig);
        this.createdAt = LocalDateTime.now();
        this.metadata = new HashMap<>(builder.metadata);
    }

    public CompletableFuture<TaskOutput> executeTaskAsync(Task task, List<TaskOutput> context) {
        return CompletableFuture.supplyAsync(() -> executeTask(task, context));
    }

    public TaskOutput executeTask(Task task, List<TaskOutput> context) {
        // If maxTurns > 1, use the reactive multi-turn loop
        if (maxTurns != null && maxTurns > 1) {
            return executeTaskReactive(task, context);
        }
        return executeSingleShot(task, context);
    }

    /**
     * Executes a task using the reactive multi-turn loop.
     * The agent can work across multiple reasoning turns, accumulating context.
     * Each turn may involve LLM calls with tool usage (handled by Spring AI).
     * The loop continues while the agent signals {@code <CONTINUE>} or until maxTurns is reached.
     */
    public TaskOutput executeTaskReactive(Task task, List<TaskOutput> context) {
        incrementExecutionCount();
        long startTime = System.currentTimeMillis();
        int turns = maxTurns != null ? maxTurns : DEFAULT_MAX_TURNS;

        try {
            AgentConversation conversation = new AgentConversation();
            String systemPrompt = buildReactiveSystemPrompt();

            for (int turn = 0; turn < turns; turn++) {
                String userPrompt;
                if (turn == 0) {
                    userPrompt = buildUserPrompt(task, context);
                } else {
                    userPrompt = buildContinuationPrompt(task, conversation);
                }

                userPrompt = enforcePromptBudget(systemPrompt, userPrompt);

                if (verbose) {
                    logger.info("Agent [{}] reactive turn {}/{} ({} chars prompt): {}",
                            role, turn + 1, turns, userPrompt.length(),
                            truncate(task.getDescription(), 80));
                }

                ChatResponse chatResponse = callLlm(systemPrompt, userPrompt);
                String response = chatResponse.getResult().getOutput().getText();

                long promptTokens = extractTokenCount(chatResponse, true);
                long completionTokens = extractTokenCount(chatResponse, false);

                conversation.addTurn(new ConversationTurn(
                        turn, response, promptTokens, completionTokens, System.currentTimeMillis()));

                if (verbose) {
                    logger.info("Agent [{}] turn {} complete ({} chars, continuation={})",
                            role, turn + 1,
                            response != null ? response.length() : 0,
                            AgentConversation.shouldContinue(response));
                }

                if (!AgentConversation.shouldContinue(response)) {
                    break;
                }

                // Auto-compaction: summarize older turns when token budget is exceeded
                if (compactionConfig.enabled()
                        && ConversationCompactor.shouldCompact(conversation, compactionConfig)) {
                    CompactionResult compactionResult =
                            ConversationCompactor.compact(conversation, compactionConfig);
                    if (compactionResult.wasCompacted()) {
                        conversation.applyCompaction(compactionResult);
                        if (verbose) {
                            logger.info("Agent [{}] auto-compacted: removed {} turns, {} active turns remain",
                                    role, compactionResult.removedTurnCount(),
                                    conversation.getActiveTurnCount());
                        }
                    }
                }
            }

            String finalResponse = conversation.getFinalResponse();
            long executionTimeMs = System.currentTimeMillis() - startTime;

            saveToMemory(task, finalResponse, executionTimeMs);

            if (verbose) {
                logger.info("Agent [{}] reactive execution done: {} turns, {} ms, {} total tokens",
                        role, conversation.getTurnCount(), executionTimeMs,
                        conversation.getCumulativeTotalTokens());
            }

            TaskOutput.Builder outputBuilder = TaskOutput.builder()
                    .agentId(id)
                    .taskId(task.getId())
                    .rawOutput(finalResponse)
                    .description(task.getDescription())
                    .summary(extractSummary(finalResponse))
                    .executionTimeMs(executionTimeMs)
                    .promptTokens(conversation.getCumulativePromptTokens())
                    .completionTokens(conversation.getCumulativeCompletionTokens())
                    .totalTokens(conversation.getCumulativeTotalTokens())
                    .metadata("turns", conversation.getTurnCount());

            if (conversation.hasBeenCompacted()) {
                outputBuilder.metadata("compactedTurns", conversation.getCompactedTurnCount());
            }

            return outputBuilder.build();

        } catch (AgentExecutionException e) {
            throw e;
        } catch (Exception e) {
            long executionTimeMs = System.currentTimeMillis() - startTime;
            logger.error("Agent [{}] reactive execution failed after {} ms: {}", role, executionTimeMs, e.getMessage());
            throw new AgentExecutionException("Failed to execute task: " + task.getId(), e, id, task.getId());
        }
    }

    /**
     * Original single-shot execution: one LLM call per task.
     */
    private TaskOutput executeSingleShot(Task task, List<TaskOutput> context) {
        incrementExecutionCount();
        long startTime = System.currentTimeMillis();

        try {
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(task, context);
            userPrompt = enforcePromptBudget(systemPrompt, userPrompt);

            if (verbose) {
                logger.info("Agent [{}] executing task ({} chars prompt, {} token context): {}",
                        role, userPrompt.length(), contextConfig.getContextWindowTokens(),
                        truncate(task.getDescription(), 80));
            }

            ChatResponse chatResponse = callLlm(systemPrompt, userPrompt);
            String response = chatResponse.getResult().getOutput().getText();
            long executionTimeMs = System.currentTimeMillis() - startTime;

            Long promptTokens = null, completionTokens = null, totalTokens = null;
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                var usage = chatResponse.getMetadata().getUsage();
                promptTokens = toLong(usage.getPromptTokens());
                completionTokens = toLong(usage.getCompletionTokens());
                totalTokens = toLong(usage.getTotalTokens());
            }

            saveToMemory(task, response, executionTimeMs);

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

        } catch (AgentExecutionException e) {
            throw e;
        } catch (Exception e) {
            long executionTimeMs = System.currentTimeMillis() - startTime;
            logger.error("Agent [{}] failed task after {} ms: {}", role, executionTimeMs, e.getMessage());
            throw new AgentExecutionException("Failed to execute task: " + task.getId(), e, id, task.getId());
        }
    }

    // ==================== LLM Call Infrastructure ====================

    /**
     * Unified LLM call method: builds the request with permitted tools (filtered by
     * permission level) and wraps dynamic tool execution with registered hooks.
     */
    private ChatResponse callLlm(String systemPrompt, String userPrompt) {
        var requestBuilder = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt);

        if (modelName != null && !modelName.isBlank()) {
            requestBuilder.options(org.springframework.ai.openai.OpenAiChatOptions.builder()
                    .model(modelName)
                    .build());
        }

        List<BaseTool> permittedTools = getPermittedTools();

        if (!permittedTools.isEmpty()) {
            List<String> springToolNames = new ArrayList<>();
            List<BaseTool> dynamicTools = new ArrayList<>();

            for (BaseTool tool : permittedTools) {
                if (tool instanceof McpToolAdapter || tool instanceof GeneratedSkill) {
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
                        callbacks.add(org.springframework.ai.tool.function.FunctionToolCallback
                                .builder(tool.getFunctionName(),
                                        (java.util.function.Function<McpToolInput, String>)
                                                input -> {
                                            Map<String, Object> params = new HashMap<>();
                                            if (input.url() != null) params.put("url", input.url());
                                            if (input.query() != null) params.put("query", input.query());
                                            if (input.input() != null) params.put("input", input.input());
                                            return executeToolWithHooks(tool, params);
                                        })
                                .description(tool.getDescription())
                                .inputType(McpToolInput.class)
                                .build());
                    } else {
                        callbacks.add(org.springframework.ai.tool.function.FunctionToolCallback
                                .builder(tool.getFunctionName(),
                                        (java.util.function.Function<DynamicToolInput, String>)
                                                input -> {
                                            Map<String, Object> params = new HashMap<>(input.getParams());
                                            return executeToolWithHooks(tool, params);
                                        })
                                .description(tool.getDescription())
                                .inputType(DynamicToolInput.class)
                                .build());
                    }
                }
                requestBuilder.toolCallbacks(callbacks.toArray(new org.springframework.ai.tool.ToolCallback[0]));
            }
        }

        // Dynamic timeout: use tracker's suggestion based on recent P95 latency
        // for this model. Explicit maxExecutionTime overrides the dynamic value.
        String latencyKey = modelName != null ? modelName : "default";
        int timeoutMs = maxExecutionTime != null
                ? maxExecutionTime
                : (int) LATENCY_TRACKER.suggestedTimeoutMs(latencyKey);

        long callStart = System.currentTimeMillis();
        ChatResponse result = callWithRetry(() -> requestBuilder.call().chatResponse(),
                DEFAULT_MAX_RETRIES, timeoutMs);
        // Record successful call latency for future timeout calculations.
        // Failures are not recorded — keeps the window biased toward realistic healthy latencies.
        LATENCY_TRACKER.recordSuccess(latencyKey, System.currentTimeMillis() - callStart);
        return result;
    }

    /**
     * Executes a tool with pre/post hook interception.
     * Pre-hooks can deny execution; post-hooks can modify output.
     */
    private String executeToolWithHooks(BaseTool tool, Map<String, Object> params) {
        // --- Pre-hooks ---
        if (!toolHooks.isEmpty()) {
            ToolHookContext preCtx = ToolHookContext.before(tool.getFunctionName(), params, id, null);
            for (ToolHook hook : toolHooks) {
                ToolHookResult result = hook.beforeToolUse(preCtx);
                if (result.action() == ToolHookResult.Action.DENY) {
                    logger.warn("Agent [{}] tool {} denied by hook: {}",
                            role, tool.getFunctionName(), result.message());
                    return "Tool execution denied: " + result.message();
                }
                if (result.action() == ToolHookResult.Action.WARN && result.message() != null) {
                    logger.warn("Agent [{}] tool {} hook warning: {}",
                            role, tool.getFunctionName(), result.message());
                }
            }
        }

        // --- Execute ---
        long toolStart = System.currentTimeMillis();
        String output;
        Throwable toolError = null;
        try {
            output = String.valueOf(tool.execute(params));
        } catch (Exception e) {
            toolError = e;
            long elapsed = System.currentTimeMillis() - toolStart;
            // Run post-hooks for error case
            if (!toolHooks.isEmpty()) {
                ToolHookContext errCtx = ToolHookContext.error(
                        tool.getFunctionName(), params, elapsed, e, id, null);
                for (ToolHook hook : toolHooks) {
                    hook.afterToolUse(errCtx);
                }
            }
            throw e;
        }
        long toolElapsed = System.currentTimeMillis() - toolStart;

        // --- Post-hooks ---
        if (!toolHooks.isEmpty()) {
            ToolHookContext postCtx = ToolHookContext.after(
                    tool.getFunctionName(), params, output, toolElapsed, id, null);
            for (ToolHook hook : toolHooks) {
                ToolHookResult result = hook.afterToolUse(postCtx);
                if (result.modifiedOutput() != null) {
                    output = result.modifiedOutput();
                }
                if (result.action() == ToolHookResult.Action.DENY) {
                    logger.warn("Agent [{}] tool {} output denied by post-hook: {}",
                            role, tool.getFunctionName(), result.message());
                    return "Tool output filtered: " + result.message();
                }
                if (result.action() == ToolHookResult.Action.WARN && result.message() != null) {
                    logger.warn("Agent [{}] tool {} post-hook warning: {}",
                            role, tool.getFunctionName(), result.message());
                }
            }
        }

        return output;
    }

    /**
     * Returns tools filtered by this agent's permission mode.
     * If no permission mode is set, all tools are returned.
     * Denied tools are logged at WARN level for auditing.
     */
    private List<BaseTool> getPermittedTools() {
        if (permissionMode == null) {
            return tools;
        }
        List<BaseTool> permitted = new ArrayList<>();
        for (BaseTool tool : tools) {
            if (tool.getPermissionLevel().isPermittedBy(permissionMode)) {
                permitted.add(tool);
            } else {
                logger.warn("[PERMISSION] Agent [{}] denied tool '{}' (requires {}, agent has {})",
                        role, tool.getFunctionName(), tool.getPermissionLevel(), permissionMode);
            }
        }
        if (permitted.size() < tools.size()) {
            logger.info("[PERMISSION] Agent [{}]: {}/{} tools permitted (mode={})",
                    role, permitted.size(), tools.size(), permissionMode);
        }
        return permitted;
    }

    private <T> T callWithRetry(Supplier<T> llmCall, int maxRetries, int timeoutMs) {
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            CompletableFuture<T> future = null;
            try {
                future = CompletableFuture.supplyAsync(llmCall::get);
                return future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                if (future != null) future.cancel(true);
                lastException = new AgentExecutionException("LLM call timed out after " + timeoutMs + "ms", e, id, null);
                if (attempt < maxRetries) {
                    long backoffMs = (long) Math.pow(2, attempt) * 1000;
                    logger.warn("Agent [{}] LLM call timed out (attempt {}/{}). Retrying in {} ms...",
                            role, attempt, maxRetries, backoffMs);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new AgentExecutionException("Interrupted during retry backoff", ie, id, null);
                    }
                }
            } catch (Exception e) {
                lastException = e;
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                String msg = cause.getMessage() != null ? cause.getMessage() : "";
                if (msg.contains("400") || msg.contains("401") || msg.contains("403")
                        || msg.contains("context_length_exceeded") || msg.contains("NonTransient")) {
                    throw new AgentExecutionException("LLM call failed (non-retryable): " + msg, e, id, null);
                }
                if (attempt < maxRetries) {
                    long backoffMs = (long) Math.pow(2, attempt) * 1000;
                    logger.warn("Agent [{}] LLM call attempt {}/{} failed: {}. Retrying in {} ms...",
                            role, attempt, maxRetries, cause.getMessage(), backoffMs);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new AgentExecutionException("Interrupted during retry backoff", ie, id, null);
                    }
                }
            }
        }
        throw new AgentExecutionException("LLM call failed after " + maxRetries + " attempts", lastException, id, null);
    }

    // ==================== Prompt Building ====================

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

    /**
     * Builds an extended system prompt with multi-turn reasoning instructions.
     */
    private String buildReactiveSystemPrompt() {
        String base = buildSystemPrompt();
        return base + "\n" +
                "MULTI-TURN REASONING:\n" +
                "You can work in multiple reasoning turns to complete complex tasks.\n" +
                "After each response:\n" +
                "- If you need to do more work (call more tools, analyze further, refine results), " +
                "end your response with <CONTINUE>\n" +
                "- When you have completed the task fully, end your response with <DONE>\n" +
                "The marker must be the last non-whitespace content in your response.\n" +
                "If you use neither marker, your response is treated as final.\n";
    }

    /**
     * Builds a continuation prompt for subsequent turns in the reactive loop,
     * including accumulated conversation context.
     */
    private String buildContinuationPrompt(Task task, AgentConversation conversation) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are continuing work on this task:\n");
        prompt.append("Task: ").append(task.getDescription()).append("\n");
        if (StringUtils.hasText(task.getExpectedOutput())) {
            prompt.append("Expected Output: ").append(task.getExpectedOutput()).append("\n");
        }
        prompt.append("\nYour previous reasoning:\n");
        prompt.append(conversation.toContextString());
        prompt.append("\n\nContinue working. Use <CONTINUE> if you need more turns, or <DONE> when finished.\n");
        return prompt.toString();
    }

    /**
     * Truncates the user prompt if it exceeds the model's context budget.
     */
    private String enforcePromptBudget(String systemPrompt, String userPrompt) {
        int maxPromptChars = contextConfig.getMaxTotalPromptChars() - systemPrompt.length();
        if (userPrompt.length() > maxPromptChars) {
            logger.warn("Agent [{}] prompt too large ({} chars), truncating to {} chars (model: {}, context: {} tokens)",
                    role, userPrompt.length(), maxPromptChars,
                    modelName != null ? modelName : "default",
                    contextConfig.getContextWindowTokens());
            return userPrompt.substring(0, maxPromptChars)
                    + "\n\n[... content truncated to fit model context window ...]";
        }
        return userPrompt;
    }

    private long extractTokenCount(ChatResponse chatResponse, boolean prompt) {
        if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
            var usage = chatResponse.getMetadata().getUsage();
            Long val = toLong(prompt ? usage.getPromptTokens() : usage.getCompletionTokens());
            return val != null ? val : 0L;
        }
        return 0L;
    }

    private void saveToMemory(Task task, String response, long executionTimeMs) {
        if (memory != null) {
            memory.save(id,
                    "Task: " + truncate(task.getDescription(), 200) +
                    "\nResult: " + truncate(response, 500),
                    Map.of("taskId", task.getId(), "executionTimeMs", executionTimeMs));
        }
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
        private Integer maxTurns;
        private PermissionLevel permissionMode;
        private List<ToolHook> toolHooks = new ArrayList<>();
        private CompactionConfig compactionConfig;

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

        /**
         * Sets the maximum number of reasoning turns for the reactive agent loop.
         * When set to a value > 1, the agent uses multi-turn execution.
         * Default: 1 (single-shot, backward compatible).
         */
        public Builder maxTurns(Integer maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        /**
         * Sets the permission mode for this agent, restricting which tools it can invoke.
         * Tools with a {@link PermissionLevel} above this mode will be filtered out.
         * Default: null (no restriction).
         */
        public Builder permissionMode(PermissionLevel permissionMode) {
            this.permissionMode = permissionMode;
            return this;
        }

        /**
         * Adds a tool hook that intercepts every tool invocation with pre/post callbacks.
         */
        public Builder toolHook(ToolHook hook) {
            this.toolHooks.add(hook);
            return this;
        }

        /**
         * Sets all tool hooks at once.
         */
        public Builder toolHooks(List<ToolHook> hooks) {
            this.toolHooks = new ArrayList<>(hooks);
            return this;
        }

        /**
         * Sets the auto-compaction configuration for the reactive agent loop.
         * Controls when older turns are summarized to reclaim context window space.
         * Default: enabled, triggers at 80% of model context window, preserves 4 recent turns.
         */
        public Builder compactionConfig(CompactionConfig compactionConfig) {
            this.compactionConfig = compactionConfig;
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
    public Integer getMaxTurns() { return maxTurns; }
    public PermissionLevel getPermissionMode() { return permissionMode; }
    public List<ToolHook> getToolHooks() { return new ArrayList<>(toolHooks); }
    public CompactionConfig getCompactionConfig() { return compactionConfig; }

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
            .maxTurns(this.maxTurns)
            .permissionMode(this.permissionMode)
            .toolHooks(this.toolHooks)
            .compactionConfig(this.compactionConfig)
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