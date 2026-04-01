# SwarmAI Framework

[![Maven CI](https://github.com/intelliswarm-ai/swarm-ai/actions/workflows/maven-ci.yml/badge.svg)](https://github.com/intelliswarm-ai/swarm-ai/actions/workflows/maven-ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-blue.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.4](https://img.shields.io/badge/Spring%20Boot-3.4.4-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring AI 1.0](https://img.shields.io/badge/Spring%20AI-1.0.4%20GA-brightgreen.svg)](https://spring.io/projects/spring-ai)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Tests](https://img.shields.io/badge/tests-783%20passing-brightgreen.svg)](#)

Java multi-agent framework with type-safe state management, self-improving skills, runtime CODE tool generation, and enterprise governance. Built on Spring AI 1.0.4 GA and Spring Boot 3.4.

## Quick Start

```xml
<!-- Add to your pom.xml -->
<dependency>
    <groupId>ai.intelliswarm</groupId>
    <artifactId>swarmai-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Optional: built-in tools (web scrape, PDF, CSV, shell, etc.) -->
<dependency>
    <groupId>ai.intelliswarm</groupId>
    <artifactId>swarmai-tools</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

```java
// Define agents with roles, goals, and tools
Agent researcher = Agent.builder()
    .role("Research Analyst")
    .goal("Find accurate, up-to-date information")
    .backstory("Experienced researcher who verifies facts from multiple sources.")
    .chatClient(chatClient)
    .tool(webSearchTool)
    .build();

Agent writer = Agent.builder()
    .role("Content Writer")
    .goal("Write clear, engaging reports")
    .backstory("Turns research into well-structured articles.")
    .chatClient(chatClient)
    .build();

// Define tasks with dependencies
Task research = Task.builder()
    .id("research")
    .description("Research the topic: {topic}")
    .expectedOutput("Key findings with sources")
    .agent(researcher)
    .build();

Task report = Task.builder()
    .id("report")
    .description("Write a report based on the research findings")
    .expectedOutput("Well-structured report in markdown")
    .agent(writer)
    .dependsOn("research")
    .outputFormat(OutputFormat.MARKDOWN)
    .build();

// Run the swarm
SwarmOutput result = Swarm.builder()
    .agents(List.of(researcher, writer))
    .tasks(List.of(research, report))
    .process(ProcessType.SEQUENTIAL)
    .build()
    .kickoff(Map.of("topic", "AI agents"));
```

> **New to SwarmAI?** Read the **[Getting Started Guide](GETTING_STARTED.md)** — a comprehensive tutorial covering agents, tasks, processes, tools, memory, knowledge, budget tracking, governance, and multi-tenancy with full code examples.

### Run Examples

Examples live in a separate repository: **[swarm-ai-examples](https://github.com/intelliswarm-ai/swarm-ai-examples)**

```bash
cd swarm-ai-examples
mvn compile exec:java -Dexec.mainClass="ai.intelliswarm.swarmai.examples.features.TypeSafeStateExample"
mvn compile exec:java -Dexec.mainClass="ai.intelliswarm.swarmai.examples.features.FunctionalGraphExample"
mvn compile exec:java -Dexec.mainClass="ai.intelliswarm.swarmai.examples.features.CheckpointExample"
```

### Build & Test

```bash
./mvnw clean test       # 783 tests, all passing
./mvnw clean install    # install to local Maven repo
```

## Project Structure

```
swarm-ai/                    (parent POM)
├── swarmai-core/            Core framework: agents, tasks, processes, state, skills,
│                            memory, knowledge, budget, governance, observability
├── swarmai-tools/           24 built-in tools (web, file, shell, PDF, CSV, etc.)
├── swarmai-studio/          Web dashboard for workflow monitoring
├── swarmai-bom/             Bill of Materials for version alignment
└── docker/                  Dockerfiles and docker-compose configs
```

Consumers pick what they need — `swarmai-tools` and `swarmai-studio` are optional.

---

## Agents

An agent is a persona with a role, goal, backstory, and capabilities. Each agent wraps a Spring AI `ChatClient` and can use tools, memory, and knowledge.

```java
Agent agent = Agent.builder()
    .role("Financial Analyst")
    .goal("Analyze financial data and identify trends")
    .backstory("CFA with 15 years of equity research experience.")
    .chatClient(chatClient)
    .tools(List.of(webSearchTool, csvTool, calculatorTool))
    .memory(memory)                // Cross-task recall
    .knowledge(knowledge)          // Reference material
    .modelName("gpt-4o")          // Override model per agent
    .temperature(0.2)              // Lower = more deterministic
    .maxExecutionTime(60_000)      // 60s timeout
    .verbose(true)
    .build();
```

Agents automatically manage context window limits per model, retry transient LLM errors with exponential backoff, and include anti-hallucination guardrails in their system prompts.

### Reactive Agent Loop (Multi-Turn Reasoning)

By default, agents execute a single LLM call per task. Enable multi-turn reasoning with `maxTurns` — the agent works across multiple reasoning steps, accumulating context between turns, until it signals completion or the turn limit is reached.

```java
Agent agent = Agent.builder()
    .role("Deep Analyst")
    .goal("Perform thorough multi-step analysis")
    .backstory("Expert at breaking complex problems into steps.")
    .chatClient(chatClient)
    .tools(List.of(webSearchTool, calculatorTool))
    .maxTurns(5)   // Up to 5 reasoning turns per task
    .build();
```

The agent uses `<CONTINUE>` and `<DONE>` markers to signal whether more work is needed. Token usage is accumulated across all turns, and the final `TaskOutput` includes `metadata("turns", N)` with the actual turn count.

## Tasks

Tasks define the work to be done. They support dependencies, conditional execution, variable interpolation, and output formatting.

```java
Task task = Task.builder()
    .id("analyze")
    .description("Analyze {company} stock performance in {year}")
    .expectedOutput("SWOT analysis in JSON format")
    .agent(analyst)
    .dependsOn("research")                              // Waits for research task
    .condition(ctx -> ctx.contains("data available"))    // Skip if no data
    .outputFormat(OutputFormat.JSON)
    .outputFile("output/analysis.json")                  // Auto-save to file
    .build();
```

## Process Types

| Process | Description |
|---------|-------------|
| `SEQUENTIAL` | Tasks run in dependency order; each receives prior outputs as context |
| `PARALLEL` | Independent tasks run concurrently in layers; tasks with dependencies wait |
| `HIERARCHICAL` | Manager agent creates a plan, delegates to workers, synthesizes results |
| `ITERATIVE` | Execute → review → refine loop until reviewer approves or max iterations reached |
| `SELF_IMPROVING` | Iterative + dynamic skill generation when the reviewer identifies capability gaps |
| `SWARM` | Distributed fan-out: discovery phase → parallel self-improving agents per target |
| `COMPOSITE` | Chain any processes into a pipeline (e.g., Parallel → Hierarchical → Iterative) |

```java
// Sequential
Swarm.builder().process(ProcessType.SEQUENTIAL)...

// Hierarchical — requires a manager agent
Swarm.builder()
    .process(ProcessType.HIERARCHICAL)
    .managerAgent(manager)
    ...

// Iterative — reviewer approves or sends feedback
Swarm.builder()
    .process(ProcessType.ITERATIVE)
    .managerAgent(reviewer)
    .config("maxIterations", 5)
    .config("qualityCriteria", "Must include data-backed claims")
    ...

// Composite — chain processes into a pipeline
Process pipeline = CompositeProcess.of(
    new ParallelProcess(agents, publisher),
    new HierarchicalProcess(agents, manager, publisher),
    new IterativeProcess(agents, reviewer, publisher, 3, null)
);
pipeline.execute(tasks, inputs, "workflow-id");
```

## Graph API (Build → Compile → Execute)

The `SwarmGraph` API provides compile-time validation and advanced features like functional nodes, conditional routing, checkpoints, and lifecycle hooks.

### Compiled Swarm

```java
CompiledSwarm swarm = SwarmGraph.create()
    .addAgent(analyst).addTask(researchTask)
    .process(ProcessType.SEQUENTIAL)
    .memory(memory)
    .compileOrThrow();  // Catches ALL config errors before execution

SwarmOutput result = swarm.kickoff(AgentState.of(Map.of("topic", "AI")));
```

### Functional Graph with Conditional Routing

```java
SwarmGraph.create()
    .addNode("classify", state -> {
        String input = state.valueOrDefault("input", "");
        return Map.of("category", classify(input));
    })
    .addNode("handle-urgent", state -> Map.of("result", handleUrgent(state)))
    .addNode("handle-normal", state -> Map.of("result", handleNormal(state)))
    .addEdge(SwarmGraph.START, "classify")
    .addConditionalEdge("classify", state ->
        "urgent".equals(state.value("category").orElse(""))
            ? "handle-urgent" : "handle-normal")
    .addEdge("handle-urgent", SwarmGraph.END)
    .addEdge("handle-normal", SwarmGraph.END)
    .compileOrThrow()
    .kickoff(AgentState.of(Map.of("input", "Server is down!")));
```

### Compilation Error Handling

```java
CompilationResult result = SwarmGraph.create()
    .process(ProcessType.HIERARCHICAL)
    .compile();
// result.errors() → [MissingManagerAgent, NoAgents, NoTasks]
// All errors reported at once — no LLM tokens burned
```

### Type-Safe State (Channel/Reducer)

```java
StateSchema schema = StateSchema.builder()
    .channel("messages", Channels.appender())     // Accumulates across agents
    .channel("tokenCount", Channels.counter())    // Sums values
    .channel("status", Channels.lastWriteWins())  // Explicit last-write-wins
    .build();

AgentState state = AgentState.of(schema, Map.of("topic", "AI"));
Optional<String> topic = state.value("topic");
AgentState updated = state.withValue("status", "COMPLETE");
```

### Checkpoints and Interrupts

Save and resume workflow state. Survive failures, restarts, and human-in-the-loop pauses.

```java
CompiledSwarm swarm = SwarmGraph.create()
    .checkpointSaver(new InMemoryCheckpointSaver())
    .interruptBefore("human-review")
    .compileOrThrow();

swarm.kickoff(inputs);   // Pauses before "human-review" task
swarm.resume("workflow-id");  // Resume after approval
```

### Lifecycle Hooks

Cross-cutting concerns at 8 lifecycle points: `BEFORE_SWARM`, `AFTER_SWARM`, `BEFORE_TASK`, `AFTER_TASK`, `BEFORE_AGENT`, `AFTER_AGENT`, `ON_ERROR`, `ON_STATE_CHANGE`.

```java
SwarmGraph.create()
    .addHook(HookPoint.BEFORE_TASK, ctx -> {
        log.info("Starting task: {}", ctx.taskId());
        return ctx.state();
    })
    .addHook(HookPoint.ON_ERROR, ctx -> {
        alerting.send("Workflow failed: " + ctx.getError().map(Throwable::getMessage));
        return ctx.state();
    })
    .compile();
```

### Diagram Generation

Generate Mermaid flowcharts from compiled workflows — renderable in GitHub, GitLab, Notion.

```java
String mermaid = new MermaidDiagramGenerator().generate(compiledSwarm);
```

## Built-in Tools (24)

All tools include routing metadata (`triggerWhen`/`avoidWhen`), SSRF protection, health checks, and output length limits. Isolated in the `swarmai-tools` module.

| Category      | Tools                                                           |
|---------------|-----------------------------------------------------------------|
| Web           | `web_search` `web_scrape` `http_request` `headless_browser` `simulated_web_search` |
| File I/O      | `file_read` `file_write` `directory_read` `pdf_read`           |
| Data          | `csv_analysis` `json_transform` `xml_parse` `database_query` `data_analysis` |
| Computation   | `calculator` `code_execution` `shell_command`                  |
| Communication | `email` `slack_webhook`                                        |
| Specialized   | `sec_filings` `report_generator` `semantic_search`             |
| Adapters      | `mcp_adapter` (Model Context Protocol)                         |

Custom tools implement `BaseTool`:

```java
@Component
public class StockPriceTool extends BaseTool {
    public String getFunctionName() { return "stock_price"; }
    public String getDescription() { return "Get current stock price for a ticker"; }
    public Object execute(Map<String, Object> params) {
        String ticker = (String) params.get("ticker");
        return fetchPrice(ticker);
    }
}
```

### Tool Permission Levels

Tools declare a permission level, and agents declare a permission mode. Tools above the agent's mode are filtered out at execution time — enabling scoped agents like read-only explorers or restricted workers.

```java
// Tool declares its permission level
public class ShellCommandTool extends BaseTool {
    @Override
    public PermissionLevel getPermissionLevel() {
        return PermissionLevel.DANGEROUS;
    }
}

// Agent restricted to read-only tools
Agent explorer = Agent.builder()
    .role("Explorer")
    .goal("Research and gather data")
    .backstory("Read-only research agent.")
    .chatClient(chatClient)
    .tools(List.of(webSearchTool, fileReadTool, shellTool))
    .permissionMode(PermissionLevel.READ_ONLY)  // shellTool filtered out
    .build();
```

| Level | Description |
|-------|-------------|
| `READ_ONLY` | Search, query, fetch — safe for any agent |
| `WORKSPACE_WRITE` | Modify files, databases, workspace state |
| `DANGEROUS` | Shell commands, network calls, deletions |
| `REQUIRES_APPROVAL` | Requires governance gate approval before execution |

### Tool Hooks (Pre/Post Interceptors)

Tool hooks wrap every tool invocation with pre/post callbacks — enabling audit logging, rate limiting, output sanitization, and cost tracking at the tool level.

```java
// Audit hook — logs every tool call
ToolHook auditHook = new ToolHook() {
    @Override
    public ToolHookResult beforeToolUse(ToolHookContext ctx) {
        log.info("Tool {} called by agent {}", ctx.toolName(), ctx.agentId());
        return ToolHookResult.allow();
    }

    @Override
    public ToolHookResult afterToolUse(ToolHookContext ctx) {
        log.info("Tool {} completed in {} ms", ctx.toolName(), ctx.executionTimeMs());
        return ToolHookResult.allow();
    }
};

// Rate-limiting hook — deny after N calls
ToolHook rateLimitHook = new ToolHook() {
    private final AtomicInteger count = new AtomicInteger();
    @Override
    public ToolHookResult beforeToolUse(ToolHookContext ctx) {
        return count.incrementAndGet() > 10
            ? ToolHookResult.deny("Rate limit exceeded")
            : ToolHookResult.allow();
    }
};

// Attach hooks to an agent
Agent agent = Agent.builder()
    .role("Guarded Agent")
    .goal("Execute with guardrails")
    .backstory("Agent with audit trail and rate limits.")
    .chatClient(chatClient)
    .toolHook(auditHook)
    .toolHook(rateLimitHook)
    .build();
```

Hook results control execution flow:

| Result | Pre-hook effect | Post-hook effect |
|--------|----------------|------------------|
| `ToolHookResult.allow()` | Proceed | Pass through output |
| `ToolHookResult.deny(reason)` | Block execution, return reason to LLM | Replace output with reason |
| `ToolHookResult.warn(msg)` | Log warning, proceed | Log warning, proceed |
| `ToolHookResult.withModifiedOutput(out)` | — | Replace tool output |

## Memory

Agents recall information from previous task executions. Results are saved automatically and relevant memories are retrieved for future tasks.

```java
// In-memory (default)
Memory memory = new InMemoryMemory();

// JDBC — persists to any relational database
Memory memory = new JdbcMemory(jdbcTemplate);

// Redis — for distributed deployments
Memory memory = new RedisMemory(redisTemplate);

// Tenant-isolated — wraps any Memory implementation
Memory memory = new TenantAwareMemory(new InMemoryMemory());
```

Assign to individual agents or share across the entire swarm:

```java
Swarm.builder()
    .memory(new InMemoryMemory())  // Shared across all agents
    ...
```

## Knowledge

Provides agents with reference material they can query during task execution. Unlike memory (execution history), knowledge contains static source documents.

```java
Knowledge knowledge = new InMemoryKnowledge();
knowledge.addSource("policy", "All reports must include an executive summary.", Map.of());

// Vector-based semantic search for large knowledge bases
Knowledge knowledge = new VectorKnowledge(embeddingClient);

// Tenant-isolated
Knowledge knowledge = new TenantAwareKnowledge(new InMemoryKnowledge());
```

## Self-Improving Workflows

Workflows that generate new CODE tools at runtime, evolve tasks across iterations, and stop when the reviewer approves.

```java
Swarm swarm = Swarm.builder()
    .agent(analyst).agent(writer).managerAgent(reviewer)
    .task(analysisTask).task(reportTask)
    .process(ProcessType.SELF_IMPROVING)
    .config("maxIterations", 15)
    .build();
// Skills are generated, verified, and hot-loaded mid-run
```

The self-improving loop:
1. Execute tasks with current tools
2. Reviewer evaluates output
3. If a **capability gap** is found → generate a new skill → validate → register → retry
4. If a **quality issue** is found → inject feedback → retry
5. If **approved** → done

Generated skills are persisted to `output/skills/` and reused across future runs. See [docs/SELF_IMPROVING_WORKFLOWS.md](docs/SELF_IMPROVING_WORKFLOWS.md) for details.

## Budget Tracking

Track and limit LLM token usage and estimated costs per workflow.

```java
BudgetTracker tracker = new InMemoryBudgetTracker();
BudgetPolicy policy = new BudgetPolicy(
    500_000,                              // Max 500K tokens
    5.0,                                  // Max $5.00 USD
    "gpt-4o",                             // Model for pricing
    BudgetPolicy.BudgetAction.HARD_STOP,  // HARD_STOP or WARN
    80.0                                  // Warn at 80% usage
);

Swarm.builder()
    .budgetTracker(tracker)
    .budgetPolicy(policy)
    ...
```

With `HARD_STOP`, the swarm throws `BudgetExceededException` when limits are reached. With `WARN`, it logs a warning and continues.

## Governance and Approval Gates

Human-in-the-loop approval checkpoints for sensitive workflows.

```java
ApprovalGate gate = new ApprovalGate(
    "publish-review", "Publish Review",
    "Requires editor approval before publishing",
    GateTrigger.BEFORE_TASK,
    Duration.ofMinutes(30),
    new ApprovalPolicy("editor", false)
);

Swarm.builder()
    .governance(governanceEngine)
    .approvalGate(gate)
    ...
```

## Multi-Tenancy

Isolate workflows, memory, knowledge, and quotas per tenant.

```java
Swarm.builder()
    .tenantId("enterprise-team")
    .tenantQuotaEnforcer(quotaEnforcer)
    .memory(new TenantAwareMemory(baseMemory))
    .knowledge(new TenantAwareKnowledge(baseKnowledge))
    ...
```

## Observability

Built-in observability with correlation IDs, structured logging, decision tracing, and event replay.

- **ObservabilityContext** — ThreadLocal context with correlation/trace/span IDs propagated across agents and tasks
- **StructuredLogger** — JSON-formatted logs enriched with workflow context
- **DecisionTracer** — Records decision points, options, and rationale for explainability
- **Event Store** — Persists workflow events for replay and debugging
- **Spring Events** — All lifecycle events (`SWARM_STARTED`, `TASK_COMPLETED`, `BUDGET_EXCEEDED`, etc.) published via `ApplicationEventPublisher`

```yaml
swarmai:
  observability:
    replay-enabled: true  # Enable event recording for replay
```

## Batch and Async Execution

```java
// Run across multiple inputs
List<SwarmOutput> results = swarm.kickoffForEach(List.of(
    Map.of("company", "Apple"),
    Map.of("company", "Google"),
    Map.of("company", "Microsoft")
));

// Async single run
CompletableFuture<SwarmOutput> future = swarm.kickoffAsync(inputs);

// Async batch
CompletableFuture<List<SwarmOutput>> futures = swarm.kickoffForEachAsync(inputsList);
```

## Output Handling

```java
SwarmOutput output = swarm.kickoff(inputs);

String result = output.getRawOutput();
boolean ok = output.isSuccessful();
long tokens = output.getTotalTokens();
double cost = output.estimateCostUsd("gpt-4o");
String summary = output.getTokenUsageSummary("gpt-4o");

// Per-task access
TaskOutput taskOut = output.getTaskOutput("research");
Map<String, Object> data = taskOut.parseAsMap();

// Typed parsing
MyReport report = output.parseAs(MyReport.class);
```

## Studio Dashboard

The optional `swarmai-studio` module provides a web UI for monitoring workflows in real-time — task timelines, agent activity, state graphs, and event streams.

```xml
<dependency>
    <groupId>ai.intelliswarm</groupId>
    <artifactId>swarmai-studio</artifactId>
</dependency>
```

## Tech Stack

| Component | Version |
|-----------|---------|
| Java | 21 |
| Spring Boot | 3.4.4 |
| Spring AI | 1.0.4 (GA) |
| MCP Java SDK | 0.10.0 |
| Groovy | 4.x (skill sandbox) |
| Build | Maven (multi-module) |
| Tests | JUnit 5 + Mockito (783 tests) |

## Documentation

- **[Getting Started Guide](GETTING_STARTED.md)** — Comprehensive tutorial with full code examples for every feature
- **[API Keys Setup](docs/API_KEYS_SETUP_GUIDE.md)** — Configure LLM provider API keys
- **[Docker Guide](docs/DOCKER_EXAMPLE_GUIDE.md)** — Run SwarmAI in Docker
- **[Self-Improving Workflows](docs/SELF_IMPROVING_WORKFLOWS.md)** — Skill generation deep dive

## Contributing

See [CONTRIBUTING.md](docs/CONTRIBUTING.md) for development setup, code style, and PR guidelines.

## License

MIT License — see [LICENSE](LICENSE) for details.
