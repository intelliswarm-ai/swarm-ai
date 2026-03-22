# SwarmAI Framework

The Java multi-agent framework that doesn't hallucinate and tells you what it costs.

Built on Spring AI 1.0.4 GA and Spring Boot 3.4. Inspired by CrewAI, designed for the Java enterprise ecosystem.

## Why SwarmAI Over Other Frameworks?

| Capability | SwarmAI | LangChain4j | Spring AI | Koog | CrewAI |
|-----------|---------|-------------|-----------|------|--------|
| Anti-hallucination guardrails | Built-in | No | No | No | No |
| Token cost tracking | Built-in | No | No | No | No |
| Parallel task execution | Yes | Yes | Manual | Yes | No |
| MCP tool integration | Yes (stdio) | Yes | Yes (1.1+) | Yes | Yes |
| Persistent memory (Redis/JDBC) | Yes | Yes | Yes | Yes | Yes |
| RAG pipeline | Yes | Advanced | Yes | Yes | Yes |
| Dynamic context management | Model-aware | No | No | History compression | No |
| Spring Boot native | Yes | Adapter | Native | Adapter | N/A (Python) |
| Process types | 3 (Seq/Hier/Parallel) | 5 | Manual | 3 | 2 |
| Language | Java 21 | Java | Java | Kotlin/Java | Python |

### What Makes SwarmAI Different

**1. Anti-Hallucination Guardrails** — Every agent automatically gets rules baked into its system prompt: date awareness, `[CONFIRMED]`/`[ESTIMATE]` markers, "DATA NOT AVAILABLE" enforcement, and unknown topic detection. No other Java framework does this at the framework level.

**2. Token Economics** — Built-in per-task token tracking with cost estimation across models (OpenAI, Anthropic, Ollama). See exactly what each agent costs.

**3. Parallel Process** — Independent tasks run concurrently with automatic dependency resolution. A 4-task due diligence workflow completes in 36 seconds instead of 112 seconds.

**4. MCP Protocol** — Connect to any MCP-compatible tool server via stdio transport. Agents automatically discover and use external tools (web fetch, search, databases).

## Quick Start

### Prerequisites

- Java 21+
- Docker (for running examples)
- OpenAI, Anthropic, or Ollama API key

### Run Examples in 30 Seconds

```bash
# Clone and configure
git clone https://github.com/intelliswarm/swarmai.git
cd swarmai
cp .env.example .env
# Edit .env — add your OPENAI_API_KEY

# Stock Analysis (parallel — 3 agents analyze AAPL simultaneously)
docker compose -f docker-compose.run.yml run --rm stock-analysis AAPL

# Due Diligence (parallel — financial + news + legal streams)
docker compose -f docker-compose.run.yml run --rm due-diligence TSLA

# Research (hierarchical — manager coordinates 4 specialists)
docker compose -f docker-compose.run.yml run --rm research "AI trends in enterprise 2026"

# MCP Research (with live web fetching via MCP tools)
docker compose -f docker-compose.run.yml run --rm mcp-research "impact of AI agents on software"
```

### Run Tests

```bash
docker compose -f docker-compose.test.yml run --rm test-unit
# 208 tests, all passing
```

## Architecture

```
Swarm (orchestrator)
├── Agent (role + goal + backstory + tools + memory)
│   ├── System prompt (anti-hallucination guardrails, date awareness)
│   ├── Tools (Spring AI functions, MCP tools)
│   ├── Memory (InMemory, Redis, JDBC)
│   └── Knowledge (InMemory, Vector Store)
├── Task (description + expected output + dependencies)
├── Process
│   ├── SEQUENTIAL — tasks run in dependency order
│   ├── HIERARCHICAL — manager plans, delegates, synthesizes
│   └── PARALLEL — independent tasks run concurrently
└── SwarmOutput (results + token usage + cost estimation)
```

### Core Components

| Component | Description |
|-----------|-------------|
| **Agent** | AI entity with role, goal, backstory. Uses system+user prompt split for proper persona adoption. |
| **Task** | Work unit with description, expected output, dependencies. Supports conditions, async, file output. |
| **Swarm** | Orchestrator combining agents + tasks + process type. Handles lifecycle, events, memory. |
| **Process** | Execution strategy. Sequential, Hierarchical (with manager), or Parallel (concurrent layers). |
| **Memory** | Agent memory across tasks. InMemory, Redis, or JDBC (PostgreSQL/MySQL). |
| **Knowledge** | Document knowledge base. InMemory keyword search or Vector Store (semantic search via Spring AI). |
| **BaseTool** | Interface for agent tools. Built-in: SEC EDGAR, web search, calculator. External: any MCP server. |

## Usage

### Basic Workflow

```java
ChatClient chatClient = chatClientBuilder.build();

Agent analyst = Agent.builder()
    .role("Financial Analyst")
    .goal("Produce accurate, evidence-based analysis. Cite every data source.")
    .backstory("CFA-certified analyst with 10 years experience. Never fabricates data.")
    .chatClient(chatClient)
    .tool(secFilingsTool)
    .modelName("gpt-4o-mini")  // enables dynamic context sizing
    .build();

Task analysisTask = Task.builder()
    .description("Analyze AAPL financial health from SEC filings")
    .expectedOutput("Report with: Revenue, Margins, Cash Flow, Risk Assessment")
    .agent(analyst)
    .build();

Swarm swarm = Swarm.builder()
    .agent(analyst)
    .task(analysisTask)
    .process(ProcessType.SEQUENTIAL)
    .eventPublisher(eventPublisher)
    .build();

SwarmOutput result = swarm.kickoff(Map.of("ticker", "AAPL"));

// Token usage and cost
System.out.println(result.getTokenUsageSummary("gpt-4o-mini"));
// Token Usage:
//   Prompt tokens:     88,833
//   Completion tokens: 3,022
//   Total tokens:      91,855
//   Estimated cost:    $0.0151 (gpt-4o-mini)
```

### Parallel Execution

```java
// Three independent research tasks run concurrently
Task financialTask = Task.builder().id("financial").agent(financialAnalyst)...build();
Task newsTask = Task.builder().id("news").agent(newsAnalyst)...build();
Task legalTask = Task.builder().id("legal").agent(legalAnalyst)...build();

// Synthesis waits for all three
Task synthesisTask = Task.builder().id("synthesis").agent(director)
    .dependsOn(financialTask)
    .dependsOn(newsTask)
    .dependsOn(legalTask)
    .build();

Swarm swarm = Swarm.builder()
    .process(ProcessType.PARALLEL)  // financial + news + legal run simultaneously
    // ...
    .build();

// Layer 0: 3 tasks in parallel (~30s)
// Layer 1: synthesis (~10s)
// Total: ~40s instead of ~120s sequential
```

### MCP Tool Integration

```java
// Connect to any MCP server and discover its tools automatically
List<BaseTool> mcpTools = McpToolAdapter.fromServer("uvx", "mcp-server-fetch");
// Discovered: fetch — Fetches a URL and extracts content as markdown

Agent researcher = Agent.builder()
    .role("Research Analyst")
    .tools(mcpTools)  // agent can now fetch live web content
    .build();
```

### Persistent Memory

```yaml
# application.yml
swarmai:
  memory:
    provider: redis    # or: jdbc, in-memory
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

```java
// Memory persists across application restarts
// Agents automatically read/write memory during task execution
```

### RAG Pipeline (Vector Knowledge)

```java
VectorKnowledge knowledge = new VectorKnowledge(vectorStore);
knowledge.addDocument(Path.of("annual_report.pdf"));
knowledge.addSource("policy", "Company travel policy content...", null);

Agent agent = Agent.builder()
    .knowledge(knowledge)  // agent queries documents via semantic search
    .build();
```

## Process Types

| Process | Use Case | How It Works |
|---------|----------|-------------|
| `SEQUENTIAL` | Pipeline workflows | Tasks run in dependency order. Each task gets prior outputs as context. |
| `HIERARCHICAL` | Coordinated teams | Manager agent plans, delegates to workers, synthesizes results. |
| `PARALLEL` | Independent research | Tasks without dependencies run concurrently. Dependency layers execute in sequence. |

## Examples

| Example | Process | What It Demonstrates | Duration | Cost |
|---------|---------|---------------------|----------|------|
| **Stock Analysis** | PARALLEL | SEC filings + web search, 3 parallel agents | ~85s | ~$0.015 |
| **Due Diligence** | PARALLEL | Financial + News + Legal streams, auto-layering | ~36s | ~$0.006 |
| **Research** | HIERARCHICAL | Manager + 4 specialists, task dependencies | ~107s | ~$0.006 |
| **MCP Research** | SEQUENTIAL | Live web fetching via MCP protocol | ~60s | ~$0.017 |

## Configuration

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4o-mini

swarmai:
  default:
    max-rpm: 30
    max-execution-time: 300000
    verbose: true

  memory:
    provider: in-memory  # Options: in-memory, redis, jdbc

  observability:
    enabled: true
    structured-logging-enabled: true
```

## Tech Stack

| Component | Version |
|-----------|---------|
| Java | 21 |
| Spring Boot | 3.4.4 |
| Spring AI | 1.0.4 (GA) |
| MCP Java SDK | 0.10.0 |
| Build | Maven |
| Tests | JUnit 5 + Mockito (208 tests) |
| Container | Docker + Docker Compose |

## Building

```bash
# Compile
./mvnw clean compile

# Run tests (requires Docker for test containers)
docker compose -f docker-compose.test.yml run --rm test-unit

# Package
./mvnw package -DskipTests
```

## Contributing

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes
4. Push to the branch
5. Open a Pull Request

## License

MIT License — see [LICENSE](LICENSE) for details.

## Credits

Inspired by and adapted from [CrewAI](https://github.com/joaomdmoura/crewAI) (MIT License). SwarmAI brings CrewAI's multi-agent patterns to the Java ecosystem with Spring AI integration, anti-hallucination guardrails, parallel execution, MCP protocol support, and token economics. See [ATTRIBUTION.md](ATTRIBUTION.md) for details.
