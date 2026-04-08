# Getting Started with SwarmAI

SwarmAI is a Java framework for building multi-agent AI workflows on Spring Boot. You define **agents** (who does the work), **tasks** (what work to do), and a **swarm** (how to coordinate them). The framework handles LLM calls, tool execution, memory, and orchestration.

## Table of Contents

- [Installation](#installation)
- [Core Concepts](#core-concepts)
- [Quick Start](#quick-start)
- [Agents](#agents)
  - [Reactive Agent Loop (Multi-Turn Reasoning)](#reactive-agent-loop-multi-turn-reasoning)
- [Tasks](#tasks)
- [Swarms and Process Types](#swarms-and-process-types)
  - [Sequential](#sequential-process)
  - [Parallel](#parallel-process)
  - [Hierarchical](#hierarchical-process)
  - [Iterative](#iterative-process)
  - [Self-Improving](#self-improving-process)
  - [Composite](#composite-process)
- [The Graph API](#the-graph-api)
- [Tools](#tools)
  - [Tool Permission Levels](#tool-permission-levels)
  - [Tool Hooks (Pre/Post Interceptors)](#tool-hooks-prepost-interceptors)
- [Memory](#memory)
- [Knowledge](#knowledge)
- [Budget Tracking](#budget-tracking)
- [Governance and Approval Gates](#governance-and-approval-gates)
- [Multi-Tenancy](#multi-tenancy)
- [Batch Execution](#batch-execution)
- [Working with Output](#working-with-output)
- [Configuration Reference](#configuration-reference)
- [YAML DSL](#yaml-dsl)

---

## Installation

Add the SwarmAI BOM and modules to your Maven project:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>ai.intelliswarm</groupId>
            <artifactId>swarmai-bom</artifactId>
            <version>${swarmai.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- Core framework -->
    <dependency>
        <groupId>ai.intelliswarm</groupId>
        <artifactId>swarmai-core</artifactId>
    </dependency>

    <!-- Built-in tools (optional) -->
    <dependency>
        <groupId>ai.intelliswarm</groupId>
        <artifactId>swarmai-tools</artifactId>
    </dependency>

    <!-- Studio UI (optional) -->
    <dependency>
        <groupId>ai.intelliswarm</groupId>
        <artifactId>swarmai-studio</artifactId>
    </dependency>
</dependencies>
```

Configure your LLM provider in `application.yml`:

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4o
```

See [API_KEYS_SETUP_GUIDE.md](docs/API_KEYS_SETUP_GUIDE.md) for details on supported providers.

---

## Core Concepts

| Concept   | What it is                                                     |
|-----------|----------------------------------------------------------------|
| **Agent** | A persona with a role, goal, and tools that calls an LLM       |
| **Task**  | A unit of work with a description and expected output          |
| **Swarm** | A group of agents and tasks, coordinated by a process type     |
| **Process** | The execution strategy (sequential, parallel, hierarchical, etc.) |
| **Tool**  | A capability an agent can use (web search, file I/O, database, etc.) |

---

## Quick Start

Here is a minimal example: two agents collaborating on a research report.

```java
@Component
public class ResearchWorkflow {

    @Autowired
    private ChatClient chatClient;

    @Autowired
    private WebSearchTool webSearchTool;

    public String run(String topic) {

        // 1. Define agents
        Agent researcher = Agent.builder()
                .role("Research Analyst")
                .goal("Find accurate, up-to-date information")
                .backstory("You are an experienced researcher who verifies facts from multiple sources.")
                .chatClient(chatClient)
                .tool(webSearchTool)
                .build();

        Agent writer = Agent.builder()
                .role("Content Writer")
                .goal("Write clear, engaging reports")
                .backstory("You turn research findings into well-structured articles.")
                .chatClient(chatClient)
                .build();

        // 2. Define tasks
        Task researchTask = Task.builder()
                .id("research")
                .description("Research the topic: {topic}")
                .expectedOutput("A list of key findings with sources")
                .agent(researcher)
                .build();

        Task writeTask = Task.builder()
                .id("write-report")
                .description("Write a report based on the research findings")
                .expectedOutput("A well-structured report in markdown format")
                .agent(writer)
                .dependsOn("research")
                .outputFormat(OutputFormat.MARKDOWN)
                .build();

        // 3. Create and run the swarm
        SwarmOutput output = Swarm.builder()
                .agents(List.of(researcher, writer))
                .tasks(List.of(researchTask, writeTask))
                .process(ProcessType.SEQUENTIAL)
                .verbose(true)
                .build()
                .kickoff(Map.of("topic", topic));

        return output.getRawOutput();
    }
}
```

That's it. The framework handles:
- Injecting the `{topic}` variable into the task description
- Running tasks in dependency order
- Passing the researcher's output as context to the writer
- Calling the LLM with appropriate system prompts and tool access

---

## Agents

An agent represents a team member with a specific role. Each agent has its own persona, capabilities, and LLM settings.

### Creating an Agent

```java
Agent agent = Agent.builder()
        .role("Data Analyst")
        .goal("Analyze datasets and extract actionable insights")
        .backstory("You have 10 years of experience in data science.")
        .chatClient(chatClient)
        .tools(List.of(csvTool, calculatorTool))
        .temperature(0.2)           // Lower = more deterministic
        .modelName("gpt-4o")        // Override the default model
        .verbose(true)              // Log detailed execution info
        .maxExecutionTime(60_000)   // 60 second timeout
        .build();
```

### Key Agent Settings

| Setting            | Default   | Description                                    |
|--------------------|-----------|------------------------------------------------|
| `role`             | required  | The agent's job title / persona                |
| `goal`             | required  | What the agent is trying to achieve            |
| `backstory`        | required  | Background context for the agent's expertise   |
| `chatClient`       | required  | Spring AI ChatClient for LLM calls             |
| `tools`            | empty     | List of tools the agent can use                |
| `modelName`        | null      | Override LLM model (uses Spring default if null) |
| `temperature`      | null      | Sampling temperature (0.0 - 1.0)              |
| `maxExecutionTime` | 300000    | Timeout in milliseconds (5 min default)        |
| `verbose`          | false     | Enable detailed logging                        |
| `memory`           | null      | Memory store for cross-task context            |
| `knowledge`        | null      | Knowledge base to query for relevant info      |
| `maxTurns`         | null      | Max reasoning turns for reactive loop (null = single-shot) |
| `permissionMode`   | null      | Max tool permission level this agent can use (null = unrestricted) |
| `toolHooks`        | empty     | Pre/post interceptors for every tool call      |

### Reactive Agent Loop (Multi-Turn Reasoning)

By default, agents make a single LLM call per task. For complex tasks that require multi-step reasoning, enable the reactive loop with `maxTurns`. The agent works across multiple turns, accumulating context, until it signals completion or the turn limit is reached.

```java
Agent deepAnalyst = Agent.builder()
        .role("Deep Analyst")
        .goal("Perform thorough multi-step analysis")
        .backstory("You break complex problems into steps and verify each one.")
        .chatClient(chatClient)
        .tools(List.of(webSearchTool, calculatorTool, csvTool))
        .maxTurns(5)     // Up to 5 reasoning turns
        .verbose(true)   // See each turn logged
        .build();
```

How it works:
1. **Turn 1**: Agent receives the task, calls tools, reasons about results
2. Agent ends its response with `<CONTINUE>` if more work is needed
3. **Turn 2+**: Agent receives its prior reasoning as context and continues
4. Agent ends with `<DONE>` (or omits any marker) to signal completion
5. Stops at `maxTurns` even if the agent wants to continue

The final `TaskOutput` includes cumulative token usage across all turns and `metadata("turns", N)` with the actual turn count.

```java
TaskOutput output = deepAnalyst.executeTask(task, context);
int turnsUsed = (int) output.getMetadata().get("turns");  // e.g., 3
long totalTokens = output.getTotalTokens();                 // sum across all turns
```

You can also call `executeTaskReactive()` directly to force multi-turn mode regardless of `maxTurns`:

```java
TaskOutput output = agent.executeTaskReactive(task, context);
```

---

## Tasks

A task describes a piece of work for an agent to complete.

### Creating a Task

```java
Task task = Task.builder()
        .id("analyze-data")
        .description("Analyze the sales data for {quarter} and identify trends")
        .expectedOutput("A JSON object with top 5 trends and their confidence scores")
        .agent(analyst)
        .outputFormat(OutputFormat.JSON)
        .outputFile("output/analysis.json")      // Save result to file
        .build();
```

### Task Dependencies

Tasks can depend on other tasks. A task won't run until all its dependencies are complete, and it automatically receives their outputs as context.

```java
Task gatherData = Task.builder()
        .id("gather")
        .description("Collect sales data from all regions")
        .agent(dataCollector)
        .build();

Task analyzeData = Task.builder()
        .id("analyze")
        .description("Analyze the collected data")
        .agent(analyst)
        .dependsOn("gather")    // Waits for "gather" to finish
        .build();

Task writeReport = Task.builder()
        .id("report")
        .description("Write a summary report")
        .agent(writer)
        .dependsOn("analyze")   // Waits for "analyze" to finish
        .build();
```

### Conditional Tasks

Tasks can be conditionally skipped based on prior output:

```java
Task detailedAnalysis = Task.builder()
        .id("detailed-analysis")
        .description("Perform deep-dive analysis")
        .agent(analyst)
        .dependsOn("initial-scan")
        .condition(context -> context.contains("anomaly detected"))
        .build();
```

This task only runs if the prior output contains "anomaly detected". Otherwise it's marked as SKIPPED.

### Variable Interpolation

Use `{variableName}` placeholders in task descriptions. They get replaced with values from the kickoff inputs:

```java
Task task = Task.builder()
        .description("Analyze {company} stock performance in {year}")
        .agent(analyst)
        .build();

// Variables are replaced at kickoff time
swarm.kickoff(Map.of("company", "Acme Corp", "year", "2025"));
```

---

## Swarms and Process Types

A swarm ties agents and tasks together with an execution strategy. The **process type** determines how tasks are coordinated.

### Sequential Process

Tasks run one after another in dependency order. Each task receives the output of its dependencies as context.

**Best for:** Pipelines where each step builds on the previous one.

```java
SwarmOutput output = Swarm.builder()
        .agents(List.of(researcher, writer, editor))
        .tasks(List.of(researchTask, writeTask, editTask))
        .process(ProcessType.SEQUENTIAL)
        .build()
        .kickoff(Map.of("topic", "AI in healthcare"));
```

```
researchTask → writeTask → editTask
```

### Parallel Process

Independent tasks run simultaneously. Tasks with dependencies wait until their dependencies complete, then run in parallel with other ready tasks.

**Best for:** Workloads where multiple independent analyses can happen at the same time.

```java
SwarmOutput output = Swarm.builder()
        .agents(List.of(marketAnalyst, techAnalyst, financeAnalyst, summarizer))
        .tasks(List.of(marketTask, techTask, financeTask, summaryTask))
        .process(ProcessType.PARALLEL)
        .build()
        .kickoff(inputs);
```

```
marketTask  ─┐
techTask    ─┼→ summaryTask
financeTask ─┘
```

Tasks are grouped into layers. All tasks in a layer run concurrently, then the next layer starts.

### Hierarchical Process

A **manager agent** creates an execution plan, assigns tasks to workers, and synthesizes the final output.

**Best for:** Complex projects where a coordinator should decide how to delegate work.

```java
Agent manager = Agent.builder()
        .role("Project Manager")
        .goal("Coordinate the research team for optimal results")
        .backstory("Senior PM with experience leading cross-functional teams.")
        .chatClient(chatClient)
        .build();

SwarmOutput output = Swarm.builder()
        .agents(List.of(researcher, analyst, writer))
        .tasks(List.of(gatherTask, analyzeTask, reportTask))
        .process(ProcessType.HIERARCHICAL)
        .managerAgent(manager)    // Required for hierarchical
        .build()
        .kickoff(inputs);
```

The flow is:
1. Manager reviews all tasks and agents, creates an execution plan
2. Worker agents execute tasks with the manager's plan as context
3. Manager reviews all results and synthesizes the final output

### Iterative Process

Tasks execute in a loop. After each round, a **reviewer agent** evaluates the output. If the quality isn't good enough, the reviewer provides feedback and tasks run again.

**Best for:** Quality-sensitive work that benefits from revision cycles (writing, analysis, code generation).

```java
Agent reviewer = Agent.builder()
        .role("Quality Reviewer")
        .goal("Ensure output meets high standards")
        .backstory("Detail-oriented editor with strict quality criteria.")
        .chatClient(chatClient)
        .build();

SwarmOutput output = Swarm.builder()
        .agents(List.of(writer))
        .tasks(List.of(draftTask))
        .process(ProcessType.ITERATIVE)
        .managerAgent(reviewer)                       // Acts as reviewer
        .config("maxIterations", 3)                   // Max review cycles
        .config("qualityCriteria", "Must include data-backed claims")
        .build()
        .kickoff(inputs);
```

The loop:
1. Execute tasks
2. Reviewer evaluates output → "APPROVED" or "NEEDS_REFINEMENT" with feedback
3. If refinement needed and iterations remain, inject feedback and re-execute
4. Stop when approved or max iterations reached

### Self-Improving Process

Extends the iterative process with **dynamic skill generation**. When the reviewer identifies a capability gap (something the agent can't do with its current tools), the framework generates a new skill, validates it, and adds it to the agent's toolkit — then retries.

**Best for:** Open-ended tasks where the required capabilities aren't known in advance.

```java
SwarmOutput output = Swarm.builder()
        .agents(List.of(analyst))
        .tasks(List.of(analysisTask))
        .process(ProcessType.SELF_IMPROVING)
        .managerAgent(reviewer)
        .config("maxIterations", 5)
        .build()
        .kickoff(inputs);
```

See [SELF_IMPROVING_WORKFLOWS.md](docs/SELF_IMPROVING_WORKFLOWS.md) for a deep dive on skill generation, validation, and persistence.

### Composite Process

Chains multiple process types together. The output of one stage feeds into the next.

**Best for:** Multi-phase workflows (e.g., parallel research → hierarchical synthesis → iterative refinement).

```java
Process research = new ParallelProcess(agents, eventPublisher);
Process synthesis = new HierarchicalProcess(agents, manager, eventPublisher);
Process refinement = new IterativeProcess(agents, reviewer, eventPublisher, 3, null);

Process pipeline = CompositeProcess.of(research, synthesis, refinement);

// Execute manually (Composite uses Process directly, not Swarm.builder())
SwarmOutput output = pipeline.execute(tasks, inputs, "my-workflow");
```

Each stage receives the prior stage's output as `__priorStageOutput` in the input map.

---

## The Graph API

For more control, use the **SwarmGraph** API. It follows a build → compile → execute lifecycle with validation before any execution happens.

### Basic Usage

```java
CompiledSwarm swarm = SwarmGraph.create()
        .addAgent(researcher)
        .addAgent(writer)
        .addTask(researchTask)
        .addTask(writeTask)
        .process(ProcessType.SEQUENTIAL)
        .memory(memory)
        .compileOrThrow();    // Validates and returns CompiledSwarm

SwarmOutput output = swarm.kickoff(Map.of("topic", "quantum computing"));
```

`compileOrThrow()` validates everything upfront — missing agents, invalid dependencies, etc. — so you catch configuration errors before execution.

### Functional Nodes and Edges

Instead of agents and tasks, you can define arbitrary processing nodes with custom logic and route between them:

```java
CompiledSwarm swarm = SwarmGraph.create()
        .addNode("classify", state -> {
            String input = state.valueOrDefault("input", "");
            String category = classify(input);
            return Map.of("category", category);
        })
        .addNode("handle-urgent", state -> {
            return Map.of("result", handleUrgent(state));
        })
        .addNode("handle-normal", state -> {
            return Map.of("result", handleNormal(state));
        })
        .addEdge(SwarmGraph.START, "classify")
        .addConditionalEdge("classify", state -> {
            String cat = (String) state.value("category").orElse("normal");
            return cat.equals("urgent") ? "handle-urgent" : "handle-normal";
        })
        .addEdge("handle-urgent", SwarmGraph.END)
        .addEdge("handle-normal", SwarmGraph.END)
        .compileOrThrow()
        .kickoff(AgentState.of(Map.of("input", "Server is down!")));
```

### Checkpoints and Interrupts

Pause execution at specific tasks for human review:

```java
CompiledSwarm swarm = SwarmGraph.create()
        .addAgent(agent)
        .addTask(draftTask)
        .addTask(publishTask)
        .process(ProcessType.SEQUENTIAL)
        .checkpointSaver(new InMemoryCheckpointSaver())
        .interruptBefore("publish")    // Pause before publishing
        .compileOrThrow();

SwarmOutput output = swarm.kickoff(inputs);
// Execution pauses before "publish" task
// Review the draft, then resume:
swarm.resume("publish");
```

### Lifecycle Hooks

Attach hooks to lifecycle events:

```java
SwarmGraph.create()
        .addAgent(agent)
        .addTask(task)
        .addHook(HookPoint.BEFORE_TASK, ctx -> {
            log.info("Starting task: {}", ctx.taskId());
            return ctx.state();    // Return (possibly modified) state
        })
        .addHook(HookPoint.AFTER_TASK, ctx -> {
            log.info("Completed task: {} in {} ms", ctx.taskId(), ctx.executionTimeMs());
            return ctx.state();
        })
        .compileOrThrow();
```

Available hook points: `BEFORE_SWARM`, `AFTER_SWARM`, `BEFORE_TASK`, `AFTER_TASK`, `BEFORE_AGENT`, `AFTER_AGENT`, `ON_ERROR`, `ON_STATE_CHANGE`.

### Compilation with Error Handling

```java
CompilationResult result = SwarmGraph.create()
        .addTask(orphanedTask)    // No agent assigned
        .compile();

if (!result.isSuccess()) {
    result.errors().forEach(error ->
        log.error("Config error: {} - {}", error.code(), error.message()));
} else {
    result.compiled().kickoff(inputs);
}
```

---

## Tools

Tools give agents capabilities beyond text generation. Each tool has a name, description, parameter schema, and execution logic.

### Built-in Tools

SwarmAI ships with 24 built-in tools in the `swarmai-tools` module:

| Category      | Tools                                                           |
|---------------|-----------------------------------------------------------------|
| Web           | `web_search`, `web_scrape`, `http_request`, `headless_browser`  |
| File I/O      | `file_read`, `file_write`, `directory_read`, `pdf_read`         |
| Data          | `csv_analysis`, `json_transform`, `xml_parse`, `database_query`, `data_analysis` |
| Computation   | `calculator`, `code_execution`, `shell_command`                 |
| Communication | `email`, `slack_webhook`                                        |
| Specialized   | `sec_filings`, `report_generator`, `semantic_search`            |

Tools are Spring beans. Inject them and assign to agents:

```java
@Autowired private WebSearchTool webSearchTool;
@Autowired private FileReadTool fileReadTool;
@Autowired private CalculatorTool calculatorTool;

Agent agent = Agent.builder()
        .role("Analyst")
        .goal("Analyze data")
        .backstory("...")
        .chatClient(chatClient)
        .tools(List.of(webSearchTool, fileReadTool, calculatorTool))
        .build();
```

### Creating Custom Tools

Implement `BaseTool`:

```java
@Component
public class StockPriceTool extends BaseTool {

    @Override
    public String getFunctionName() {
        return "stock_price";
    }

    @Override
    public String getDescription() {
        return "Get the current stock price for a given ticker symbol";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "ticker", Map.of(
                    "type", "string",
                    "description", "Stock ticker symbol (e.g., AAPL)"
                )
            ),
            "required", List.of("ticker")
        );
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String ticker = (String) parameters.get("ticker");
        // Call your stock API here
        return Map.of("ticker", ticker, "price", 150.25, "currency", "USD");
    }

    // Optional: guide the LLM on when to use this tool
    @Override
    public String getTriggerWhen() {
        return "User asks about stock prices, market data, or ticker symbols";
    }

    @Override
    public String getAvoidWhen() {
        return "User asks about cryptocurrency or forex";
    }
}
```

### MCP Tools

SwarmAI integrates with [Model Context Protocol](https://modelcontextprotocol.io/) servers via `McpToolAdapter`:

```java
McpToolAdapter mcpTool = new McpToolAdapter(
    "brave_search",
    "Search the web using Brave Search",
    "http://localhost:3000"
);

Agent agent = Agent.builder()
        .tool(mcpTool)
        // ...
        .build();
```

### Tool Permission Levels

Tools declare a permission level that indicates how sensitive or dangerous they are. Agents declare a permission mode that limits which tools they can access. Tools above the agent's mode are filtered out at execution time.

**Permission levels (ordered least to most privileged):**

| Level | Description | Example tools |
|-------|-------------|---------------|
| `READ_ONLY` | Search, query, fetch | `web_search`, `file_read`, `csv_analysis` |
| `WORKSPACE_WRITE` | Modify files, databases | `file_write`, `database_query` |
| `DANGEROUS` | Shell commands, deletions | `shell_command`, `code_execution` |
| `REQUIRES_APPROVAL` | Needs governance gate approval | Production deployments, external API mutations |

**Declaring a tool's permission level:**

```java
@Component
public class ShellCommandTool extends BaseTool {

    @Override
    public PermissionLevel getPermissionLevel() {
        return PermissionLevel.DANGEROUS;
    }

    // ... other methods
}
```

Tools default to `READ_ONLY` if not overridden.

**Restricting an agent:**

```java
// This agent can only use READ_ONLY tools
Agent explorer = Agent.builder()
        .role("Explorer")
        .goal("Gather data without modifying anything")
        .backstory("Read-only research agent.")
        .chatClient(chatClient)
        .tools(List.of(webSearchTool, fileReadTool, shellTool))
        .permissionMode(PermissionLevel.READ_ONLY)
        .build();
// shellTool is silently filtered out at execution time
```

```java
// This agent can use READ_ONLY and WORKSPACE_WRITE tools
Agent builder = Agent.builder()
        .role("Builder")
        .goal("Create and modify files")
        .backstory("Builder agent with write access.")
        .chatClient(chatClient)
        .tools(List.of(webSearchTool, fileWriteTool, shellTool))
        .permissionMode(PermissionLevel.WORKSPACE_WRITE)
        .build();
// shellTool (DANGEROUS) is filtered out; webSearchTool and fileWriteTool remain
```

If no `permissionMode` is set, the agent has access to all tools (no filtering).

### Tool Hooks (Pre/Post Interceptors)

Tool hooks wrap every individual tool invocation with pre/post callbacks. They enable audit logging, rate limiting, output sanitization, and cost tracking at the tool level.

**The `ToolHook` interface:**

```java
public interface ToolHook {
    default ToolHookResult beforeToolUse(ToolHookContext context) {
        return ToolHookResult.allow();
    }
    default ToolHookResult afterToolUse(ToolHookContext context) {
        return ToolHookResult.allow();
    }
}
```

**Example: Audit logging hook**

```java
ToolHook auditHook = new ToolHook() {
    @Override
    public ToolHookResult beforeToolUse(ToolHookContext ctx) {
        log.info("TOOL_CALL agent={} tool={} params={}",
                ctx.agentId(), ctx.toolName(), ctx.inputParams());
        return ToolHookResult.allow();
    }

    @Override
    public ToolHookResult afterToolUse(ToolHookContext ctx) {
        log.info("TOOL_RESULT agent={} tool={} time={}ms error={}",
                ctx.agentId(), ctx.toolName(), ctx.executionTimeMs(), ctx.hasError());
        return ToolHookResult.allow();
    }
};
```

**Example: Rate-limiting hook**

```java
ToolHook rateLimitHook = new ToolHook() {
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public ToolHookResult beforeToolUse(ToolHookContext ctx) {
        if (calls.incrementAndGet() > 20) {
            return ToolHookResult.deny("Rate limit: max 20 tool calls per task");
        }
        return ToolHookResult.allow();
    }
};
```

**Example: Output sanitization hook**

```java
ToolHook sanitizeHook = new ToolHook() {
    @Override
    public ToolHookResult afterToolUse(ToolHookContext ctx) {
        if (ctx.output() != null && ctx.output().contains("API_KEY=")) {
            return ToolHookResult.withModifiedOutput(
                    ctx.output().replaceAll("API_KEY=\\S+", "API_KEY=[REDACTED]"));
        }
        return ToolHookResult.allow();
    }
};
```

**Attaching hooks to an agent:**

```java
Agent agent = Agent.builder()
        .role("Guarded Agent")
        .goal("Execute with audit trail and guardrails")
        .backstory("Agent with hooks for compliance.")
        .chatClient(chatClient)
        .tools(List.of(webSearchTool, fileReadTool))
        .toolHook(auditHook)
        .toolHook(rateLimitHook)
        .toolHook(sanitizeHook)
        .build();
```

Hooks are called in registration order. Multiple hooks chain — if any pre-hook returns `DENY`, execution is blocked immediately.

**Hook result actions:**

| Result | Pre-hook | Post-hook |
|--------|----------|-----------|
| `ToolHookResult.allow()` | Proceed with execution | Pass through original output |
| `ToolHookResult.deny(reason)` | Block execution; reason returned to LLM | Replace output with reason |
| `ToolHookResult.warn(message)` | Log warning, proceed | Log warning, proceed |
| `ToolHookResult.withModifiedOutput(out)` | N/A | Replace tool output |

**`ToolHookContext` fields:**

| Field | Description |
|-------|-------------|
| `toolName()` | Name of the tool being called |
| `inputParams()` | Parameters passed to the tool |
| `output()` | Tool output (null for pre-hooks) |
| `executionTimeMs()` | Execution duration (0 for pre-hooks) |
| `error()` | Exception if tool threw (null otherwise) |
| `agentId()` | ID of the agent invoking the tool |
| `workflowId()` | Workflow ID (may be null) |

Hooks are preserved when calling `agent.withAdditionalTools()` (used by self-improving workflows).

---

## Memory

Memory lets agents recall information from previous task executions. When an agent completes a task, the result is saved. On future tasks, relevant memories are retrieved and included in the prompt.

### In-Memory (Default)

```java
Memory memory = new InMemoryMemory();

Agent agent = Agent.builder()
        .role("Assistant")
        .goal("Help users")
        .backstory("...")
        .chatClient(chatClient)
        .memory(memory)
        .build();
```

Or share memory across the entire swarm:

```java
Swarm swarm = Swarm.builder()
        .agents(List.of(agent1, agent2))
        .tasks(List.of(task1, task2))
        .memory(new InMemoryMemory())
        .build();
```

### Persistent Memory

For production, use JDBC or Redis:

```java
// JDBC - persists to any relational database
Memory memory = new JdbcMemory(jdbcTemplate);

// Redis - for distributed deployments
Memory memory = new RedisMemory(redisTemplate);
```

### Memory API

```java
memory.save("agent-1", "Completed financial analysis", Map.of("sector", "tech"));
List<String> results = memory.search("financial analysis", 5);
List<String> recent = memory.getRecentMemories("agent-1", 10);
memory.clearForAgent("agent-1");
```

---

## Knowledge

Knowledge provides agents with reference information. Unlike memory (which stores execution history), knowledge contains static source material the agent can query during tasks.

```java
Knowledge knowledge = new InMemoryKnowledge();

// Add sources
knowledge.addSource("company-policy",
        "All reports must include an executive summary and data sources.",
        Map.of("type", "policy"));

knowledge.addSource("product-catalog",
        "Product A: Enterprise analytics platform. Product B: Data pipeline tool.",
        Map.of("type", "catalog"));

// Assign to an agent
Agent agent = Agent.builder()
        .knowledge(knowledge)
        // ...
        .build();
```

When the agent executes a task, it automatically queries the knowledge base for content relevant to the task description and includes it in the prompt.

For semantic search over large knowledge bases, use `VectorKnowledge` with embedding-based retrieval.

---

## Budget Tracking

Track and limit LLM token usage and estimated costs.

```java
BudgetTracker tracker = new InMemoryBudgetTracker();

BudgetPolicy policy = new BudgetPolicy(
        500_000,                    // Max 500K tokens
        5.0,                       // Max $5.00 USD
        "gpt-4o",                  // Model for cost calculation
        BudgetPolicy.BudgetAction.HARD_STOP,  // Stop on exceed (or WARN)
        80.0                       // Warn at 80% usage
);

SwarmOutput output = Swarm.builder()
        .agents(List.of(agent))
        .tasks(List.of(task))
        .budgetTracker(tracker)
        .budgetPolicy(policy)
        .build()
        .kickoff(inputs);

// Check usage after execution
BudgetSnapshot snapshot = tracker.getSnapshot(swarm.getId());
System.out.println("Tokens used: " + snapshot.totalTokensUsed());
System.out.println("Estimated cost: $" + snapshot.estimatedCostUsd());
```

With `HARD_STOP`, the swarm throws `BudgetExceededException` when limits are reached. With `WARN`, it logs a warning and continues.

---

## Governance and Approval Gates

Add human-in-the-loop approval checkpoints to your workflows.

```java
ApprovalGate reviewGate = new ApprovalGate(
        "review-gate",
        "Human Review",
        "Requires human approval before publishing",
        GateTrigger.BEFORE_TASK,
        Duration.ofMinutes(30),     // Timeout
        new ApprovalPolicy("editor", false)
);

Swarm swarm = Swarm.builder()
        .agents(List.of(writer))
        .tasks(List.of(draftTask, publishTask))
        .process(ProcessType.SEQUENTIAL)
        .governance(governanceEngine)
        .approvalGate(reviewGate)
        .build();
```

The workflow pauses at the gate and waits for approval. If denied or timed out, a `GovernanceException` is thrown.

---

## Multi-Tenancy

Isolate workflows, memory, and quotas per tenant in shared deployments.

```java
// Per-tenant quotas
TenantQuotaEnforcer quotaEnforcer = new InMemoryTenantQuotaEnforcer(
        Map.of(
            "tenant-a", new TenantResourceQuota(10, 1_000_000),  // 10 concurrent, 1M tokens
            "tenant-b", new TenantResourceQuota(5, 500_000)
        )
);

// Tenant-isolated memory
Memory tenantMemory = new TenantAwareMemory(new InMemoryMemory());

Swarm swarm = Swarm.builder()
        .agents(List.of(agent))
        .tasks(List.of(task))
        .tenantId("tenant-a")
        .tenantQuotaEnforcer(quotaEnforcer)
        .memory(tenantMemory)
        .build()
        .kickoff(inputs);
```

Each tenant's memory is isolated. If a tenant exceeds their quota, `TenantQuotaExceededException` is thrown before execution starts.

---

## Batch Execution

Run the same workflow across multiple inputs:

```java
Swarm swarm = Swarm.builder()
        .agents(List.of(analyst))
        .tasks(List.of(analysisTask))
        .process(ProcessType.SEQUENTIAL)
        .build();

List<SwarmOutput> results = swarm.kickoffForEach(List.of(
        Map.of("company", "Apple"),
        Map.of("company", "Google"),
        Map.of("company", "Microsoft")
));

// Or async
CompletableFuture<List<SwarmOutput>> futureResults =
        swarm.kickoffForEachAsync(inputsList);
```

Tasks are automatically reset between iterations so the swarm can be reused.

---

## Working with Output

### SwarmOutput

The result of a swarm execution:

```java
SwarmOutput output = swarm.kickoff(inputs);

// Final output text
String result = output.getRawOutput();

// Check success
boolean ok = output.isSuccessful();

// Token usage
long totalTokens = output.getTotalTokens();

// Cost estimate
double cost = output.estimateCostUsd("gpt-4o");

// Detailed per-task breakdown
String summary = output.getTokenUsageSummary("gpt-4o");

// Access individual task outputs
TaskOutput researchResult = output.getTaskOutput("research");

// Parse structured output
MyReport report = output.parseAs(MyReport.class);
```

### TaskOutput

Each task produces a `TaskOutput`:

```java
TaskOutput taskOut = output.getTaskOutput("analyze");

String raw = taskOut.getRawOutput();
String summary = taskOut.getSummary();
long execMs = taskOut.getExecutionTimeMs();
long promptTokens = taskOut.getPromptTokens();

// Parse JSON output
Map<String, Object> data = taskOut.parseAsMap();
MyData typed = taskOut.parseAsType(MyData.class);
```

### Saving Output to Files

Set `outputFile` on a task to automatically save results:

```java
Task task = Task.builder()
        .description("Generate a report")
        .agent(writer)
        .outputFile("output/report.md")
        .outputFormat(OutputFormat.MARKDOWN)
        .build();
```

---

## Configuration Reference

### application.yml

```yaml
swarmai:
  workflow:
    max-iterations: 3               # Default max iterations for iterative/self-improving
    quality-criteria: null           # Custom quality criteria for reviews

  budget:
    enabled: true
    max-total-tokens: 1000000       # 1M tokens
    max-cost-usd: 10.0              # $10
    on-exceeded: WARN               # WARN or HARD_STOP

  governance:
    enabled: false

  tenant:
    enabled: false

  observability:
    replay-enabled: false            # Enable event replay/recording

spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4o
```

### Profiles

| Profile  | Description                                    |
|----------|------------------------------------------------|
| `local`  | Development with relaxed settings              |
| `docker` | Containerized deployment                       |
| `test`   | Testing with mock LLM clients                  |
| `run`    | Deployment configuration                       |

---

## Full Example: Research Pipeline

Here's a complete example that brings together agents, tasks, tools, memory, and budget tracking:

```java
@Service
public class CompanyResearchPipeline {

    @Autowired private ChatClient chatClient;
    @Autowired private WebSearchTool webSearchTool;
    @Autowired private FileWriteTool fileWriteTool;

    public SwarmOutput analyze(String company) {

        // Shared memory across agents
        Memory memory = new InMemoryMemory();

        // Budget control
        BudgetTracker tracker = new InMemoryBudgetTracker();
        BudgetPolicy policy = new BudgetPolicy(
                200_000, 2.0, "gpt-4o",
                BudgetPolicy.BudgetAction.HARD_STOP, 80.0);

        // Agents
        Agent researcher = Agent.builder()
                .role("Market Researcher")
                .goal("Find comprehensive market data about companies")
                .backstory("You specialize in gathering financial and market intelligence.")
                .chatClient(chatClient)
                .tool(webSearchTool)
                .memory(memory)
                .temperature(0.3)
                .build();

        Agent analyst = Agent.builder()
                .role("Financial Analyst")
                .goal("Analyze financial data and identify trends")
                .backstory("CFA with 15 years of experience in equity research.")
                .chatClient(chatClient)
                .memory(memory)
                .temperature(0.2)
                .build();

        Agent writer = Agent.builder()
                .role("Report Writer")
                .goal("Create clear, professional investment reports")
                .backstory("Former journalist, now writes for institutional investors.")
                .chatClient(chatClient)
                .tool(fileWriteTool)
                .memory(memory)
                .build();

        // Tasks
        Task research = Task.builder()
                .id("research")
                .description("Research {company}: market position, competitors, recent news, financials")
                .expectedOutput("Structured research notes with data points and sources")
                .agent(researcher)
                .outputFormat(OutputFormat.JSON)
                .build();

        Task analyze = Task.builder()
                .id("analyze")
                .description("Analyze the research data. Identify strengths, risks, and outlook.")
                .expectedOutput("SWOT analysis with confidence ratings")
                .agent(analyst)
                .dependsOn("research")
                .outputFormat(OutputFormat.JSON)
                .build();

        Task report = Task.builder()
                .id("report")
                .description("Write an investment report combining research and analysis")
                .expectedOutput("Professional report with executive summary, analysis, and recommendation")
                .agent(writer)
                .dependsOn("analyze")
                .outputFormat(OutputFormat.MARKDOWN)
                .outputFile("output/" + company.toLowerCase() + "-report.md")
                .build();

        // Run
        return Swarm.builder()
                .agents(List.of(researcher, analyst, writer))
                .tasks(List.of(research, analyze, report))
                .process(ProcessType.SEQUENTIAL)
                .memory(memory)
                .budgetTracker(tracker)
                .budgetPolicy(policy)
                .verbose(true)
                .build()
                .kickoff(Map.of("company", company));
    }
}
```

---

## YAML DSL

The `swarmai-dsl` module lets you define workflows in YAML instead of Java. This dramatically reduces boilerplate — a 60-line Java workflow becomes a single YAML file loaded in 2 lines of code.

### Setup

Add the DSL dependency to your project:

```xml
<dependency>
    <groupId>ai.intelliswarm</groupId>
    <artifactId>swarmai-dsl</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

The module includes Spring Boot auto-configuration. When you add it to your classpath, a `SwarmLoader` bean is automatically available for injection.

### Basic Usage

**1. Define your workflow in YAML:**

```yaml
# src/main/resources/workflows/research.yaml
swarm:
  name: "Research Pipeline"
  process: SEQUENTIAL

  agents:
    researcher:
      role: "Senior Researcher"
      goal: "Find comprehensive information on {{topic}}"
      backstory: "Expert researcher with deep analytical skills"
      maxTurns: 3
      temperature: 0.7

    writer:
      role: "Technical Writer"
      goal: "Create clear, well-structured reports"
      backstory: "Skilled writer who transforms research into readable content"

  tasks:
    research:
      description: "Research {{topic}} thoroughly"
      expectedOutput: "Detailed research summary"
      agent: researcher

    report:
      description: "Write a final report"
      expectedOutput: "Well-formatted report"
      agent: writer
      dependsOn: [research]
      outputFormat: MARKDOWN
      outputFile: "output/report.md"
```

**2. Load and run:**

```java
@Autowired SwarmLoader swarmLoader;

Swarm swarm = swarmLoader.load("workflows/research.yaml",
    Map.of("topic", "AI Safety"));
SwarmOutput output = swarm.kickoff(Map.of());
```

Template variables like `{{topic}}` are substituted at load time.

### Programmatic Usage (Without Spring)

```java
YamlSwarmParser parser = new YamlSwarmParser();
SwarmCompiler compiler = SwarmCompiler.builder()
    .chatClient(chatClient)
    .eventPublisher(eventPublisher)
    .tool("web-search", webSearchTool)
    .build();

SwarmLoader loader = new SwarmLoader(parser, compiler);
Swarm swarm = loader.load("workflows/research.yaml",
    Map.of("topic", "AI Safety"));
```

### All Supported Features

#### Agent Configuration

```yaml
agents:
  analyst:
    role: "Data Analyst"
    goal: "Analyze data thoroughly"
    backstory: "Expert analyst with 10 years experience"
    model: "anthropic/claude-sonnet-4-20250514"
    maxTurns: 3
    temperature: 0.2
    verbose: true
    allowDelegation: false
    maxExecutionTime: 300
    maxRpm: 15
    permissionMode: READ_ONLY     # READ_ONLY, WORKSPACE_WRITE, DANGEROUS
    memory: true                   # Enable agent-level memory
    knowledge: true                # Enable agent-level knowledge
    tools: [web-search, calculator]
    compaction:
      enabled: true
      preserveRecentTurns: 4
      thresholdTokens: 80000
    toolHooks:
      - type: audit
      - type: sanitize
        patterns: ["\\b[\\w.+-]+@[\\w-]+\\.[a-z]{2,}\\b"]
      - type: rate-limit
        maxCalls: 10
        windowSeconds: 30
      - type: deny
        tools: [shell-command]
```

#### Task Configuration

```yaml
tasks:
  analyze:
    description: "Analyze {{topic}} in depth"
    expectedOutput: "Comprehensive analysis report"
    agent: analyst
    dependsOn: [gather-data]
    outputFormat: MARKDOWN         # TEXT, JSON, MARKDOWN
    outputFile: "output/analysis.md"
    asyncExecution: false
    maxExecutionTime: 600
    condition: "contains('risk')"  # Skip unless prior output contains 'risk'
    tools: [calculator]
```

#### Budget Tracking

```yaml
budget:
  maxTokens: 100000
  maxCostUsd: 5.0
  onExceeded: WARN              # WARN or HARD_STOP
  warningThresholdPercent: 80.0
```

#### Governance and Approval Gates

```yaml
governance:
  approvalGates:
    - name: "Quality Review"
      trigger: AFTER_TASK          # BEFORE_TASK, AFTER_TASK, BEFORE_SKILL_PROMOTION, etc.
      timeoutMinutes: 30
      policy:
        requiredApprovals: 2
        approverRoles: [tech-lead, security-reviewer]
        autoApproveOnTimeout: false
```

#### Workflow Hooks

```yaml
hooks:
  - point: BEFORE_WORKFLOW
    type: log
    message: "Workflow started"
  - point: AFTER_TASK
    type: checkpoint
  - point: ON_ERROR
    type: log
    message: "Error occurred"
```

#### Knowledge Sources

```yaml
knowledgeSources:
  - id: "architecture-guide"
    content: "The system uses a microservices architecture with..."
  - id: "best-practices"
    content: "Always validate inputs at service boundaries..."
```

### Process Types

All 7 process types are supported:

```yaml
# Simple sequential
swarm:
  process: SEQUENTIAL

# Parallel execution
swarm:
  process: PARALLEL

# Manager coordinates workers
swarm:
  process: HIERARCHICAL
  managerAgent: manager

# Iterative refinement with reviewer
swarm:
  process: ITERATIVE
  managerAgent: reviewer
  config:
    maxIterations: 3
    qualityCriteria: "Must include data sources"

# Self-improving with skill generation
swarm:
  process: SELF_IMPROVING
  managerAgent: reviewer

# Distributed fan-out
swarm:
  process: SWARM
  managerAgent: coordinator
  config:
    maxParallelAgents: 5
    targetPrefix: "TARGET:"

# Multi-stage pipeline
swarm:
  process: COMPOSITE
  stages:
    - process: PARALLEL
    - process: HIERARCHICAL
      managerAgent: manager
    - process: ITERATIVE
      managerAgent: reviewer
      maxIterations: 3
```

### Graph Workflows (Conditional Routing)

For workflows that need conditional routing, feedback loops, or state-based decisions, use the `graph:` section instead of `process:`:

```yaml
swarm:
  name: "Quality Gate Workflow"

  state:
    channels:
      score:
        type: lastWriteWins
      feedback:
        type: stringAppender
      iteration:
        type: counter

  agents:
    writer:
      role: "Writer"
      goal: "Write articles"
      backstory: "Skilled content writer"
    evaluator:
      role: "Evaluator"
      goal: "Score articles 0-100"
      backstory: "Strict quality reviewer"
    optimizer:
      role: "Optimizer"
      goal: "Improve articles based on feedback"
      backstory: "Content optimization specialist"

  graph:
    nodes:
      write:
        agent: writer
        task: "Write an article about the topic"
      evaluate:
        agent: evaluator
        task: "Score the article 0-100"
      optimize:
        agent: optimizer
        task: "Improve the article based on feedback"
    edges:
      - from: START
        to: write
      - from: write
        to: evaluate
      - from: evaluate
        conditional:
          - when: "score >= 80"
            to: END
          - when: "iteration >= 3"
            to: END
          - default: optimize
      - from: optimize
        to: evaluate
```

**Supported channel types:** `lastWriteWins`, `appender`, `counter`, `stringAppender`

**Supported condition expressions:**
- Numeric: `round < 3`, `score >= 80`
- String: `category == "BILLING"`
- Boolean: `approved == true`
- Combined: `score >= 80 || iteration >= 3`

Load graph workflows with `compileWorkflow()`:

```java
CompiledWorkflow workflow = swarmLoader.loadWorkflow("workflows/graph.yaml");
SwarmOutput output = workflow.kickoff(Map.of("topic", "AI Safety"));
```

### Example YAML Files

The **[swarm-ai-examples](https://github.com/intelliswarm-ai/swarm-ai-examples)** repository includes 30 YAML workflow definitions under `src/main/resources/workflows/`, covering every feature and process type.

---

## Next Steps

- [API_KEYS_SETUP_GUIDE.md](docs/API_KEYS_SETUP_GUIDE.md) — Configure LLM provider API keys
- [DOCKER_EXAMPLE_GUIDE.md](docs/DOCKER_EXAMPLE_GUIDE.md) — Run SwarmAI in Docker
- [SELF_IMPROVING_WORKFLOWS.md](docs/SELF_IMPROVING_WORKFLOWS.md) — Deep dive into self-improving processes and skill generation
- **[YAML Workflow Examples](https://github.com/intelliswarm-ai/swarm-ai-examples)** — 30 ready-to-use YAML workflow definitions
