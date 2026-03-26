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
| Iterative refinement loops | Yes (reviewer-driven) | No | No | No | No |
| Self-improving workflows | Yes (runtime tool generation) | No | No | No | No |
| Process types | 5 (Seq/Hier/Parallel/Iterative/Self-Improving) | 5 | Manual | 3 | 2 |
| Language | Java 21 | Java | Java | Kotlin/Java | Python |

### What Makes SwarmAI Different

**1. Anti-Hallucination Guardrails** — Every agent automatically gets rules baked into its system prompt: date awareness, `[CONFIRMED]`/`[ESTIMATE]` markers, "DATA NOT AVAILABLE" enforcement, and unknown topic detection. No other Java framework does this at the framework level.

**2. Token Economics** — Built-in per-task token tracking with cost estimation across models (OpenAI, Anthropic, Ollama). See exactly what each agent costs.

**3. Parallel Process** — Independent tasks run concurrently with automatic dependency resolution. A 4-task due diligence workflow completes in 36 seconds instead of 112 seconds.

**4. Iterative Refinement** — Tasks execute, a reviewer agent evaluates output against a quality rubric, and the loop repeats with feedback until approved. Cyclic workflows with a structured review protocol.

**5. Self-Improving Workflows** — The killer feature. An LLM planner dynamically designs agents, tasks, and tool selection based on any user query. When the reviewer identifies a missing capability, new Groovy tools are generated, validated in a sandbox, and hot-loaded into agents at runtime. Skills persist to disk and are reused across runs. No hardcoded domain logic — the same workflow handles financial analysis, competitive intelligence, or any other task.

**6. MCP Protocol** — Connect to any MCP-compatible tool server via stdio transport. Agents automatically discover and use external tools (web fetch, search, databases).

**7. Tool Library (15+ Built-in Tools)** — Production-ready tools for file I/O, web scraping, HTTP requests, shell commands, CSV/JSON/XML processing, PDF reading, code execution, and more. All registered as Spring beans with proper sandboxing and validation.

**8. SwarmAI Studio** — Built-in web dashboard at `/studio` for real-time workflow visualization. Graph view of agent/task relationships, event timeline, token usage charts, and cost analysis. Available on every workflow run.

### Competitive Analysis: Where SwarmAI Wins

#### vs CrewAI (Python)

CrewAI pioneered the multi-agent pattern and SwarmAI draws inspiration from it. But CrewAI is Python-only, has no parallel execution, and offers no runtime tool generation. SwarmAI brings CrewAI's patterns to Java with significant additions:

| Area | SwarmAI | CrewAI |
|------|---------|--------|
| Runtime tool generation | Groovy skills generated, validated, hot-loaded | No — tools must be defined before execution |
| Parallel execution | Automatic dependency-based layering | Sequential only |
| Anti-hallucination | Framework-level guardrails in every prompt | Manual prompt engineering |
| Token tracking | Per-task with cost estimation | Basic token counts |
| Iterative refinement | Structured reviewer loop with quality rubric | No built-in review cycle |
| Ecosystem | Spring Boot, Spring AI, Maven, enterprise Java | Python, LangChain |

#### vs LangChain4j

LangChain4j is a mature Java library with strong RAG and chain composition. However, it focuses on chain-of-thought workflows rather than multi-agent orchestration:

| Area | SwarmAI | LangChain4j |
|------|---------|-------------|
| Multi-agent orchestration | First-class (Agent + Task + Process) | Chains, no agent abstraction |
| Process strategies | 5 built-in (including self-improving) | Manual composition |
| Self-improving loops | Automatic gap detection + skill generation | No |
| Observability dashboard | Built-in Studio UI | No built-in UI |
| Anti-hallucination | Automatic system prompt guardrails | Manual |
| Tool generation | Runtime Groovy skill creation | No |

#### vs Spring AI (standalone)

Spring AI provides the model abstraction layer that SwarmAI builds on. Using Spring AI alone requires manual orchestration of agents, tasks, and workflows:

| Area | SwarmAI | Spring AI |
|------|---------|-----------|
| Agent abstraction | Role/Goal/Backstory pattern with tools, memory | ChatClient only |
| Task orchestration | Dependency resolution, parallel, iterative | Manual coding |
| Process types | 5 declarative strategies | DIY |
| Built-in tools | 15+ production-ready tools | Function callbacks (bring your own) |
| Studio dashboard | Built-in at /studio | No |
| Skill generation | Automatic from capability gaps | No |

#### vs Koog (JetBrains)

Koog is a Kotlin-first agent framework from JetBrains with strong typing and structured output. It focuses on single-agent workflows with tool use:

| Area | SwarmAI | Koog |
|------|---------|------|
| Multi-agent coordination | Manager/worker patterns, parallel teams | Single-agent focus |
| Self-improving | Runtime tool generation + iterative refinement | No |
| Process types | 5 (including self-improving) | 3 |
| Spring ecosystem | Native Spring Boot | Kotlin adapter |
| Built-in tools | 15+ tools | Minimal |
| Studio UI | Built-in dashboard | No |

#### The Self-Improving Advantage

No other open-source framework — in any language — offers runtime tool generation from capability gaps. This is SwarmAI's core differentiator:

```
Traditional framework:  Query → [fixed tools] → Result
SwarmAI:                Query → Plan → Execute → Review
                                         ↓ (capability gap detected)
                                   Generate Groovy tool → Validate → Register
                                         ↓
                                   Rebuild agents → Re-execute with new tool
                                         ↓
                                   Better result (skills persist for future runs)
```

The same workflow code handles financial analysis, security assessments, competitive intelligence, or any other domain — the LLM planner adapts at runtime.

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

# Iterative Memo (execute → review → refine loop, 3 iterations max)
docker compose -f docker-compose.run.yml run --rm iterative-memo NVDA 3

# Self-Improving — dynamic workflow adaptation (the killer feature)
# Give it ANY query — it plans agents, selects tools, generates skills at runtime
docker compose -f docker-compose.run.yml run --rm --service-ports self-improving \
  "Compare the top 5 AI coding assistants for enterprise Java development"

docker compose -f docker-compose.run.yml run --rm --service-ports self-improving \
  "Analyze the competitive landscape of cloud providers AWS vs Azure vs GCP"

# Open Studio UI to watch the workflow in real-time:
# http://localhost:8080/studio
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
│   ├── Tools (Spring AI functions, MCP tools, Generated Skills)
│   ├── Memory (InMemory, Redis, JDBC)
│   └── Knowledge (InMemory, Vector Store)
├── Task (description + expected output + dependencies)
├── Process
│   ├── SEQUENTIAL — tasks run in dependency order
│   ├── HIERARCHICAL — manager plans, delegates, synthesizes
│   ├── PARALLEL — independent tasks run concurrently
│   ├── ITERATIVE — execute → review → refine loop until approved
│   └── SELF_IMPROVING — dynamic planning + skill generation + iterative refinement
├── Skill System (runtime tool generation)
│   ├── SkillGenerator — LLM generates Groovy tools from capability gaps
│   ├── SkillValidator — security scan + syntax check + sandboxed testing
│   ├── SkillRegistry — storage, deduplication, usage tracking, persistence
│   └── GeneratedSkill — sandboxed Groovy execution with tool composition
├── Studio (web dashboard at /studio)
│   ├── Workflow graph visualization
│   ├── Event timeline and live SSE streaming
│   └── Token usage charts and cost analysis
└── SwarmOutput (results + token usage + cost estimation + skill metadata)
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
| **BaseTool** | Interface for agent tools. 15+ built-in tools, MCP server integration, runtime-generated Groovy skills. |
| **GeneratedSkill** | Dynamically created tool backed by sandboxed Groovy code. Can compose existing tools. Persists to disk. |
| **Studio** | Web dashboard at `/studio` for workflow visualization, event timeline, token charts, and cost analysis. |

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

### Iterative Refinement

```java
// Reviewer agent evaluates output against quality criteria.
// Loop repeats with feedback until APPROVED or max iterations reached.
Agent researcher = Agent.builder().role("Research Analyst")...build();
Agent writer = Agent.builder().role("Memo Writer")...build();
Agent reviewer = Agent.builder().role("Managing Director")...build(); // reviewer

Task researchTask = Task.builder().id("research").agent(researcher)...build();
Task memoTask = Task.builder().id("memo").agent(writer).dependsOn(researchTask)...build();

Swarm swarm = Swarm.builder()
    .agent(researcher).agent(writer).agent(reviewer)
    .task(researchTask).task(memoTask)
    .process(ProcessType.ITERATIVE)
    .managerAgent(reviewer)          // reviewer agent
    .config("maxIterations", 3)
    .config("qualityCriteria", "Must have data tables, 5+ risks, peer comparison")
    .build();

SwarmOutput result = swarm.kickoff(inputs);
// result.getUsageMetrics() → {iterations: 2, approved: true, totalTasks: 6}
```

### Self-Improving Workflows

```java
// The workflow dynamically adapts to ANY query — no hardcoded domain logic.
// An LLM planner determines agents, tasks, tools, and quality criteria at runtime.
// When the reviewer identifies missing capabilities, new Groovy tools are generated,
// validated in a sandbox, and hot-loaded into agents.

Swarm swarm = Swarm.builder()
    .agent(analyst)       // role/goal determined by planner
    .agent(writer)
    .managerAgent(reviewer)
    .task(analysisTask)   // description generated by planner
    .task(reportTask)
    .process(ProcessType.SELF_IMPROVING)
    .config("maxIterations", 3)
    .config("qualityCriteria", plannerGeneratedCriteria)
    .build();

SwarmOutput result = swarm.kickoff(inputs);
// result.getMetadata() → {skillsGenerated: 3, totalIterations: 3,
//   registryStats: {totalSkills: 3, byStatus: {ACTIVE: 3}, averageEffectiveness: 1.0}}

// Generated skills are persisted to output/skills/ and reused in future runs.
// Example skills auto-generated: nmap_vulnerability_report, financial_data_aggregator
```

**How it works:**
1. **Plan** — LLM planner analyzes the query and available tools, generates agent roles, task descriptions, and quality criteria
2. **Execute** — Agents run tasks using selected tools
3. **Review** — Reviewer evaluates output, identifies QUALITY_ISSUES and CAPABILITY_GAPS
4. **Generate** — For each gap, a new Groovy tool is generated, security-scanned, syntax-checked, and sandboxed-tested
5. **Rebuild** — Agents are rebuilt with the new tool added to their toolkit
6. **Repeat** — Loop continues with expanded capabilities until approved or max iterations

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
| `ITERATIVE` | Quality-gated output | Tasks execute, reviewer evaluates against rubric, loop repeats with feedback until approved or max iterations. |
| `SELF_IMPROVING` | Dynamic adaptation | LLM plans the workflow, agents execute, reviewer detects capability gaps, new tools are generated/validated/hot-loaded, agents rebuilt with expanded toolkit. Skills persist across runs. |

## Examples

| Example | Process | What It Demonstrates | Duration | Cost |
|---------|---------|---------------------|----------|------|
| **Stock Analysis** | PARALLEL | SEC filings + web search, 3 parallel agents | ~85s | ~$0.015 |
| **Due Diligence** | PARALLEL | Financial + News + Legal streams, auto-layering | ~36s | ~$0.006 |
| **Research** | HIERARCHICAL | Manager + 4 specialists, task dependencies | ~107s | ~$0.006 |
| **MCP Research** | SEQUENTIAL | Live web fetching via MCP protocol | ~60s | ~$0.017 |
| **Iterative Memo** | ITERATIVE | Reviewer-driven refinement loop, 7-point quality rubric | ~226s | ~$0.030 |
| **Self-Improving** | SELF_IMPROVING | Dynamic planning, runtime skill generation, any query | ~90-530s | ~$0.002-0.005 |
| **Codebase Analysis** | PARALLEL | Multi-agent code review with file/directory tools | ~60s | ~$0.010 |
| **Web Research** | HIERARCHICAL | Manager + researchers + fact-checker | ~80s | ~$0.008 |
| **Data Pipeline** | SEQUENTIAL | CSV analysis + transformation + reporting | ~45s | ~$0.004 |

## Built-in Tool Library

| Tool | Description |
|------|-------------|
| `web_search` | Multi-source web search (Google, Bing, NewsAPI) with financial data enrichment |
| `web_scrape` | URL content extraction with HTML-to-text conversion and SSRF protection |
| `http_request` | HTTP GET/POST/PUT/DELETE with headers, body, and timeout support |
| `shell_command` | Whitelisted shell commands (nmap, ping, git, docker, etc.) with timeout |
| `calculator` | Mathematical expression evaluation |
| `file_read` | Read files with line range support |
| `file_write` | Write/append to files with directory creation |
| `directory_read` | List directory contents with metadata |
| `csv_analysis` | CSV parsing, statistics, filtering, and aggregation |
| `json_transform` | JSONPath queries, field extraction, array operations |
| `xml_parse` | XPath queries on XML documents |
| `pdf_read` | PDF text extraction with page range support |
| `code_execution` | Sandboxed code execution (Java, Python, Groovy) |
| `sec_filings` | SEC EDGAR API integration for financial filings |
| `report_generator` | Structured report generation from data |

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

## SwarmAI Studio

Built-in web dashboard available at `http://localhost:8080/studio` on every workflow run (use `--service-ports` with Docker).

- **Workflow Graph** — Interactive visualization of agents, tasks, and their relationships using vis-network
- **Event Timeline** — Real-time event stream via SSE showing task starts, completions, iterations, and skill generation
- **Token Dashboard** — Per-task token usage, prompt vs completion breakdown, cost estimation charts
- **Task Detail Panel** — Click any node to inspect: agent role, duration, tokens, tool calls, and full output

```bash
# Run any workflow with Studio enabled
docker compose -f docker-compose.run.yml run --rm --service-ports self-improving "your query"
# Open: http://localhost:8080/studio
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
