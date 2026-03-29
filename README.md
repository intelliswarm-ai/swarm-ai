# SwarmAI Framework

[![Maven CI](https://github.com/intelliswarm-ai/swarm-ai/actions/workflows/maven-ci.yml/badge.svg)](https://github.com/intelliswarm-ai/swarm-ai/actions/workflows/maven-ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-blue.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.4](https://img.shields.io/badge/Spring%20Boot-3.4.4-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring AI 1.0](https://img.shields.io/badge/Spring%20AI-1.0.4%20GA-brightgreen.svg)](https://spring.io/projects/spring-ai)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Tests](https://img.shields.io/badge/tests-744%20passing-brightgreen.svg)](#)

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
// Classic API
Swarm swarm = Swarm.builder()
    .agent(analyst).agent(writer)
    .task(researchTask).task(reportTask)
    .process(ProcessType.SEQUENTIAL)
    .build();
SwarmOutput result = swarm.kickoff(Map.of("topic", "AI agents"));

// New: Type-safe compiled API with validation
CompiledSwarm swarm = SwarmGraph.create()
    .addAgent(analyst).addTask(researchTask)
    .checkpointSaver(new InMemoryCheckpointSaver())
    .addHook(HookPoint.AFTER_TASK, ctx -> {
        log.info("Completed: {}", ctx.taskId());
        return ctx.state();
    })
    .compileOrThrow();  // catches ALL config errors before execution
SwarmOutput result = swarm.kickoff(AgentState.of(Map.of("topic", "AI")));

// New: Lambda-based functional graph API
SwarmGraph.create()
    .addNode("research", state -> Map.of("findings", doResearch(state)))
    .addNode("write", state -> Map.of("report", writeReport(state)))
    .addEdge(SwarmGraph.START, "research")
    .addConditionalEdge("research", state ->
        state.valueOrDefault("done", false) ? "write" : "research")
    .addEdge("write", SwarmGraph.END)
    .compile();
```

### Run Examples

Examples live in a separate repository: **[swarm-ai-examples](https://github.com/intelliswarm-ai/swarm-ai-examples)**

```bash
# Framework feature demos (no LLM keys needed)
cd swarm-ai-examples
mvn compile exec:java -Dexec.mainClass="ai.intelliswarm.swarmai.examples.features.TypeSafeStateExample"
mvn compile exec:java -Dexec.mainClass="ai.intelliswarm.swarmai.examples.features.FunctionalGraphExample"
mvn compile exec:java -Dexec.mainClass="ai.intelliswarm.swarmai.examples.features.CheckpointExample"
```

### Build & Test

```bash
./mvnw clean test       # 744 tests, all passing
./mvnw clean install    # install to local Maven repo
```

## Project Structure

```
swarm-ai/                    (parent POM)
├── swarmai-core/            Core framework: agents, tasks, processes, state, skills,
│                            memory, knowledge, budget, governance, observability
├── swarmai-tools/           24 built-in tools (web, file, shell, PDF, CSV, etc.)
├── swarmai-studio/          Optional web dashboard
├── swarmai-bom/             Bill of Materials for version alignment
└── docker/                  Dockerfiles and docker-compose configs
```

Consumers pick what they need — `swarmai-tools` and `swarmai-studio` are optional.

## Core Features

### Type-Safe State (Channel/Reducer)

Replaces raw `Map<String, Object>` with typed access and channel-based merge semantics for concurrent updates.

```java
StateSchema schema = StateSchema.builder()
    .channel("messages", Channels.appender())     // accumulates across agents
    .channel("tokenCount", Channels.counter())    // sums values
    .channel("status", Channels.lastWriteWins())  // explicit last-write-wins
    .build();

AgentState state = AgentState.of(schema, Map.of("topic", "AI"));
Optional<String> topic = state.value("topic");              // type-safe, never NPE
long tokens = state.valueOrDefault("tokenCount", 0L);       // default fallback
AgentState updated = state.withValue("status", "COMPLETE");  // immutable update
```

### Sealed Lifecycle (Build → Compile → Execute)

`SwarmGraph` (mutable builder) → `compile()` → `CompiledSwarm` (frozen executor). Invalid configurations fail at compile time with ALL errors collected, not at runtime one-at-a-time.

```java
CompilationResult result = SwarmGraph.create()
    .process(ProcessType.HIERARCHICAL)
    // forgot manager agent, agents, and tasks
    .compile();

// result.errors() → [MissingManagerAgent, NoAgents, NoTasks]
// All 3 errors reported at once — no LLM tokens burned
```

### Checkpoint Persistence

Save and resume workflow state. Survive failures, restarts, and human-in-the-loop pauses.

```java
CompiledSwarm swarm = SwarmGraph.create()
    .checkpointSaver(new InMemoryCheckpointSaver())
    .interruptBefore("human-review")  // pause for approval
    .compileOrThrow();

// Resume from last checkpoint after restart
swarm.resume("workflow-id");
```

### Hook System

Unified cross-cutting concerns via `@FunctionalInterface` hooks at 8 lifecycle points.

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
String diagram = new MermaidDiagramGenerator().generate(compiledSwarm);
// Paste into any ```mermaid code block
```

### Process Composition

Chain multiple processes into a pipeline.

```java
Process pipeline = CompositeProcess.of(
    new SequentialProcess(agents, publisher),
    new HierarchicalProcess(agents, manager, publisher)
);
```

### Self-Improving Workflows

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

### Enterprise Governance

Multi-tenancy, budget tracking with WARN/HARD_STOP, and human-in-the-loop approval gates.

```java
Swarm.builder()
    .tenantId("enterprise-team")
    .budgetTracker(tracker).budgetPolicy(policy)
    .governance(engine).approvalGate(gate)
    .build();
```

## Process Types

| Process | Use Case |
|---------|----------|
| `SEQUENTIAL` | Tasks run in dependency order, each gets prior outputs |
| `HIERARCHICAL` | Manager delegates to workers, synthesizes results |
| `PARALLEL` | Independent tasks run concurrently |
| `ITERATIVE` | Execute → review → refine loop until approved |
| `SELF_IMPROVING` | Dynamic planning + CODE skill generation + task evolution |
| `COMPOSITE` | Chain any processes: Sequential → Hierarchical → Iterative |

## Built-in Tools (24)

All tools include routing metadata, SSRF protection, and health checks. Isolated in `swarmai-tools` module.

`web_search` `web_scrape` `http_request` `shell_command` `calculator` `code_execution` `file_read` `file_write` `directory_read` `csv_analysis` `json_transform` `xml_parse` `pdf_read` `database_query` `sec_filings` `data_analysis` `semantic_search` `report_generator` `email` `slack_webhook` `headless_browser` `simulated_web_search` `mcp_adapter`

## Tech Stack

| Component | Version |
|-----------|---------|
| Java | 21 |
| Spring Boot | 3.4.4 |
| Spring AI | 1.0.4 (GA) |
| MCP Java SDK | 0.10.0 |
| Groovy | 4.x (skill sandbox) |
| Build | Maven (multi-module) |
| Tests | JUnit 5 + Mockito (744 tests) |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, code style, and PR guidelines.

## License

MIT License — see [LICENSE](LICENSE) for details.
