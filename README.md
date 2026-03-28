# SwarmAI Framework

[![Maven CI](https://github.com/intelliswarm-ai/swarm-ai/actions/workflows/maven-ci.yml/badge.svg)](https://github.com/intelliswarm-ai/swarm-ai/actions/workflows/maven-ci.yml)
[![Publish Snapshot](https://github.com/intelliswarm-ai/swarm-ai/actions/workflows/maven-snapshot.yml/badge.svg)](https://github.com/intelliswarm-ai/swarm-ai/actions/workflows/maven-snapshot.yml)
[![Qodana](https://github.com/intelliswarm-ai/swarm-ai/actions/workflows/qodana_code_quality.yml/badge.svg)](https://github.com/intelliswarm-ai/swarm-ai/actions/workflows/qodana_code_quality.yml)
[![Java 21](https://img.shields.io/badge/Java-21-blue.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.4](https://img.shields.io/badge/Spring%20Boot-3.4.4-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring AI 1.0](https://img.shields.io/badge/Spring%20AI-1.0.4%20GA-brightgreen.svg)](https://spring.io/projects/spring-ai)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Tests](https://img.shields.io/badge/tests-643%20passing-brightgreen.svg)](#)

The Java multi-agent framework with self-improving skills, runtime CODE tool generation, reviewer-driven task evolution, and enterprise governance.

Built on Spring AI 1.0.4 GA and Spring Boot 3.4, designed for the Java enterprise ecosystem.

## Why SwarmAI Over Other Frameworks?

| Capability | SwarmAI | LangChain4j | Spring AI | Koog |
|-----------|---------|-------------|-----------|------|
| Anti-hallucination guardrails | Built-in | No | No | No |
| Token cost tracking | Built-in | No | No | No |
| Parallel task execution | Yes | Yes | Manual | Yes |
| MCP tool integration | Yes (stdio) | Yes | Yes (1.1+) | Yes |
| Persistent memory (Redis/JDBC) | Yes | Yes | Yes | Yes |
| RAG pipeline | Yes | Advanced | Yes | Yes |
| Dynamic context management | Model-aware | No | No | History compression |
| Spring Boot native | Yes | Adapter | Native | Adapter |
| Iterative refinement loops | Yes (reviewer-driven) | No | No | No |
| Self-improving workflows | Yes (CODE skill generation, quality-gated) | No | No | No |
| Runtime tool generation | Yes (Groovy CODE skills with formal verification) | No | No | No |
| Reviewer-driven task evolution | Yes (tasks mutate across iterations) | No | No | No |
| Tool routing metadata | Yes (triggerWhen/avoidWhen/categories) | No | No | No |
| Tool health checks | Yes (pre-flight validation) | No | No | No |
| Enterprise governance | Yes (multi-tenancy, budgets, approval gates) | No | No | No |
| Process types | 5 (Seq/Hier/Parallel/Iterative/Self-Improving) | 5 | Manual | 3 |
| Language | Java 21 | Java | Java | Kotlin/Java |

### How SwarmAI Differs from LangGraph Reflection

LangGraph's reflection pattern runs Agent -> Critic -> Agent in a fixed loop. The critic says "try again" and the agent retries with the same tools. SwarmAI goes further:

| Aspect | LangGraph Reflection | SwarmAI Self-Improving |
|--------|---------------------|----------------------|
| Tools across iterations | Static (same tools every iteration) | Expanding (new CODE skills generated mid-run) |
| Task description | Fixed (same task retried) | Evolving (mutated by reviewer feedback + command ledger) |
| Critic output | Text feedback ("try again") | Structured: QUALITY_ISSUES + CAPABILITY_GAPS + NEXT_COMMANDS |
| Stopping condition | Fixed iteration count | Goal-driven (reviewer APPROVED or convergence detected) |
| Generated capabilities | None | Real Groovy CODE tools with formal verification |
| Skill persistence | N/A | Skills saved to disk, reused across runs |

### What Makes SwarmAI Different

**1. Self-Improving Skill Architecture** — The core differentiator. When a reviewer identifies a missing capability, the framework generates new CODE skills at runtime — real Groovy tools that compose existing tools, parse output, and perform data processing. Skills are formally verified before deployment.

| Skill Type | What It Does | Best For |
|-----------|-------------|---------|
| **CODE** (preferred) | Sandboxed Groovy script with tool composition and library access | Data transformation, output parsing, tool pipelines |
| **HYBRID** | Code gathers data, instructions guide LLM reasoning | Complex analysis needing both data processing and reasoning |
| **COMPOSITE** | Router dispatching to sub-skills based on intent | Multi-capability domains with distinct sub-tasks |

Generated CODE skills have access to `groovy.json.JsonSlurper`, `java.util.regex`, `java.net.URLEncoder`, and `java.time.*` for real data processing. Skills are persisted as directory-based packages (SKILL.md + _meta.json), versioned with SemVer, and reused across runs.

**Example generated skills from a real penetration testing run:**
- `tool_chain_executor` — Composes multiple shell commands into a pipeline with output parsing
- `hydra_brute_force_wrapper` — Automates credential testing with wordlist generation
- `auto_enumerate_scan_exploit` — Full scan-enumerate-exploit pipeline per host

**2. Quality-Gated Skill Generation with Formal Verification** — Skills must pass a multi-stage validation pipeline before deployment:

- **Meta-Skill Rejection** — Blocks "methodology" or "workflow guide" skills that teach the LLM what it already knows
- **CODE-First Policy** — PROMPT-only skills are blocked (score < 0.70 threshold). Real capability gaps need real CODE tools
- **Gap Analyzer** — Scores gaps on clarity, coverage, novelty, complexity, and reuse potential (0-1.0)
- **Tool-Error Detection** — Rejects gaps that describe I/O errors or connection failures (tool usage problems, not missing capabilities)
- **Impossible-Capability Blocklist** — Blocks requests for sentiment analysis APIs, social media feeds, or ML infrastructure
- **Cross-Iteration Dedup** — Prevents the same gap from being processed twice across iterations
- **Formal Verification** — CODE skills require at least 2 test cases with assertion-based verification. Skills with no tests are capped at grade F
- **Quality Scoring** — 5-dimension assessment (documentation, test coverage, error handling, complexity, output format). Minimum grade C (70/100) for promotion

**3. Reviewer-Driven Task Evolution** — Unlike LangGraph reflection where agents retry the same task, SwarmAI **mutates the task itself** across iterations:

- After each review, the analysis task description is replaced with an evolved version
- Previous work is tracked in a **Command Ledger** — structured list of executed commands with status and results
- The reviewer generates **NEXT_COMMANDS** — specific shell commands the agent must execute next iteration
- The agent sees "COMMANDS ALREADY EXECUTED (DO NOT REPEAT)" + "MANDATORY COMMANDS TO EXECUTE"
- Result: each iteration pushes forward (discovery → enumeration → exploitation) instead of repeating

```
Iteration 1: nmap -sP 192.168.1.0/24 → discover 10 hosts
Iteration 2: nmap --script http-enum,http-vuln-* → NSE vulnerability scripts
Iteration 4: hydra -L users.txt -P passwords.txt smb → credential brute-force
Iteration 6: nikto -host per-host → web vulnerability scanning
```

**4. Tool Routing Metadata** — Every tool declares when to use it and when not to, enabling accurate LLM tool selection:

```java
@Override
public String getTriggerWhen() {
    return "User asks about stock prices, market data, or financial news.";
}

@Override
public String getAvoidWhen() {
    return "Question is about local files, math, or code execution.";
}
```

Tools also declare categories, tags, requirements (env vars, binaries), output schemas, and smoke tests. The agent's system prompt includes structured `USE WHEN` / `AVOID WHEN` routing hints per tool.

**5. Anti-Hallucination Guardrails** — Every agent automatically gets rules baked into its system prompt: date awareness, `[CONFIRMED]`/`[ESTIMATE]` markers, "DATA NOT AVAILABLE" enforcement, and unknown topic detection. URL validation rejects hallucinated API domains (`api.example.com`, `api.cloudmarketshare.com`) with helpful suggestions for real endpoints.

**6. Token Economics** — Built-in per-task token tracking with cost estimation across models (OpenAI, Anthropic, Ollama). See exactly what each agent costs. Budget policies with WARN or HARD_STOP actions prevent runaway spending.

**7. Enterprise Governance** — Production-ready multi-tenancy, budget tracking, and approval gates:
- **Multi-Tenancy** — Isolated resource quotas per team (max workflows, skills, token budgets)
- **Budget Tracking** — Real-time token/cost monitoring with configurable limits and auto-stop
- **Approval Gates** — Human-in-the-loop review points with timeout-based auto-approval for demos

**8. Tool Health Checks** — Before assigning tools to agents, the framework verifies they're operational:
```
Tool health check: 7/8 tools operational
  web_search UNHEALTHY: [Missing environment variable: ALPHA_VANTAGE_API_KEY]
```
Unhealthy tools are excluded pre-flight, preventing wasted LLM calls on tools that will fail.

**9. 21+ Built-in Tools** — Production-ready tools for file I/O, web scraping, HTTP requests, shell commands, CSV/JSON/XML processing, PDF reading, SEC filings, code execution, and more. All with routing metadata, SSRF protection, and proper sandboxing.

**10. MCP Protocol** — Connect to any MCP-compatible tool server via stdio transport. Agents automatically discover and use external tools.

**11. SwarmAI Studio** — Built-in web dashboard at `/studio` for real-time workflow visualization, event timeline, token usage charts, and cost analysis.

## Quick Start

### Prerequisites

- Java 21+
- Docker (for running examples)
- OpenAI, Anthropic, or Ollama API key

### Run Examples in 30 Seconds

```bash
# Clone and configure
git clone https://github.com/intelliswarm-ai/swarm-ai.git
cd swarm-ai
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

# Iterative Memo (execute -> review -> refine loop, 3 iterations max)
docker compose -f docker-compose.run.yml run --rm iterative-memo NVDA 3

# Self-Improving — dynamic workflow adaptation with CODE skill generation
# Give it ANY query — it plans agents, generates CODE tools at runtime, evolves tasks
docker compose -f docker-compose.run.yml run --rm --service-ports self-improving \
  "Compare the top 5 AI coding assistants for enterprise Java development"

# Penetration Testing — self-improving security assessment
# Discovery → enumeration → exploitation with hydra/nikto/nmap scripts
docker compose -f docker-compose.run.yml run --rm --service-ports self-improving \
  "Scan my home network 192.168.1.0/24 and test all devices for vulnerabilities"

# Enterprise Governed — self-improving + multi-tenancy + budgets + approval gates
docker compose -f docker-compose.run.yml run --rm --service-ports enterprise-governed \
  "Analyze the competitive landscape of cloud providers AWS vs Azure vs GCP"

# Open Studio UI to watch the workflow in real-time:
# http://localhost:8080/studio
```

### Run Tests

```bash
./mvnw clean test
# 643 tests, all passing
```

## Architecture

```
Swarm (orchestrator)
├── Agent (role + goal + backstory + tools + memory)
│   ├── System prompt (anti-hallucination guardrails, date awareness, tool routing hints)
│   ├── Tools (Spring AI functions, MCP tools, Generated Skills)
│   │   ├── Tool routing: triggerWhen / avoidWhen / category / tags
│   │   ├── Tool requirements: env vars, binaries, services
│   │   ├── Tool health checks: pre-flight validation
│   │   └── Output schema: format description for downstream parsing
│   ├── Memory (InMemory, Redis, JDBC)
│   └── Knowledge (InMemory, Vector Store)
├── Task (description + expected output + dependencies)
├── Process
│   ├── SEQUENTIAL — tasks run in dependency order
│   ├── HIERARCHICAL — manager plans, delegates, synthesizes
│   ├── PARALLEL — independent tasks run concurrently
│   ├── ITERATIVE — execute -> review -> refine loop until approved
│   └── SELF_IMPROVING — dynamic planning + quality-gated skill generation
├── Skill System
│   ├── SkillDefinition — Rich SKILL.md-format packages (frontmatter + body + code + resources)
│   ├── SkillType — CODE (preferred) | HYBRID | COMPOSITE (PROMPT blocked for capability gaps)
│   ├── SkillGapAnalyzer — Quality gate: meta-skill rejection, CODE-first policy, clarity, coverage, novelty
│   ├── SkillGenerator — LLM generates CODE skills with library access (JsonSlurper, regex, URLEncoder)
│   ├── SkillValidator — Formal verification: security scan + syntax + sandboxed tests (min 2 test cases)
│   ├── SkillQualityScore — 5-dimension scoring; CODE skills with no tests capped at grade F
│   ├── SkillVersion — SemVer tracking with rollback support
│   ├── SkillRegistry — Storage, dedup, category search, tag filtering, persistence as packages
│   ├── GeneratedSkill — 4 execution modes with tool composition, references, resources, sub-skills
│   └── ReviewResult — Parses VERDICT, QUALITY_ISSUES, CAPABILITY_GAPS, and NEXT_COMMANDS
├── Enterprise
│   ├── Multi-Tenancy — per-tenant resource quotas and isolation
│   ├── Budget Tracking — per-workflow token/cost monitoring with WARN/HARD_STOP
│   └── Governance — approval gates with human-in-the-loop review
├── Observability
│   ├── DecisionTracer — agent decision tracking
│   ├── EventStore — workflow replay
│   └── StructuredLogger — JSON logging
├── Studio (web dashboard at /studio)
│   ├── Workflow graph visualization
│   ├── Event timeline and live SSE streaming
│   └── Token usage charts and cost analysis
└── SwarmOutput (results + token usage + cost estimation + skill metadata)
```

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
    .modelName("gpt-4o-mini")
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
System.out.println(result.getTokenUsageSummary("gpt-4o-mini"));
```

### Self-Improving Workflows

```java
// The workflow dynamically adapts to ANY query — no hardcoded domain logic.
// Tasks evolve across iterations. CODE skills are generated and formally verified.
// The reviewer prescribes specific commands for the next iteration.

Swarm swarm = Swarm.builder()
    .agent(analyst)       // role/goal determined by LLM planner
    .agent(writer)
    .managerAgent(reviewer)
    .task(analysisTask)   // description evolves each iteration
    .task(reportTask)
    .process(ProcessType.SELF_IMPROVING)
    .config("maxIterations", 15)  // safety cap — goal-driven stopping
    .config("qualityCriteria", plannerGeneratedCriteria)
    .build();

SwarmOutput result = swarm.kickoff(inputs);
// result.getMetadata() -> {skillsGenerated: 3, skillsReused: 0,
//   registryStats: {totalSkills: 3, byCategory: {data-io: 2, generated: 1},
//   averageQuality: 73.0, averageEffectiveness: 1.0}}
```

**How the self-improving loop works:**
1. **Plan** — LLM planner (gpt-4.1) analyzes the query and available tools, generates agent roles, phased task descriptions, and quality criteria
2. **Health Check** — Tools verified as operational before agent assignment
3. **Execute** — Agents run tasks using selected tools (shell_command, http_request, etc.)
4. **Tool Evidence Check** — Verifies agents actually used tools (not just LLM knowledge)
5. **Review + NEXT_COMMANDS** — Reviewer evaluates output, identifies QUALITY_ISSUES and CAPABILITY_GAPS, and prescribes specific commands for the next iteration
6. **Reclassify** — Tool errors reclassified as quality issues (not capability gaps)
7. **Gap Analyze** — Each gap scored; meta-skills blocked; CODE type enforced
8. **Dedup** — Cross-iteration dedup prevents same gap from being processed twice
9. **Generate CODE Skill** — For approved gaps, a CODE/HYBRID skill is generated with library access (JsonSlurper, regex, URLEncoder)
10. **Formal Verification** — Security scan + syntax check + sandboxed test execution + quality scoring (minimum grade C)
11. **Task Evolution** — Analysis task is rewritten: command ledger shows completed work, reviewer's NEXT_COMMANDS become mandatory, agent pushes forward instead of repeating
12. **Convergence** — Loop stops when reviewer approves OR after 3 stale iterations (no output growth + repeated gaps)

### Enterprise Governance

```java
// Multi-tenancy + budget tracking + approval gates
Swarm swarm = Swarm.builder()
    .agent(analyst).agent(writer).managerAgent(reviewer)
    .task(analysisTask).task(reportTask)
    .process(ProcessType.SELF_IMPROVING)
    // Enterprise features
    .tenantId("enterprise-team")
    .tenantQuotaEnforcer(quotaEnforcer)    // max workflows, skills, tokens per tenant
    .budgetTracker(budgetTracker)           // real-time token/cost monitoring
    .budgetPolicy(budgetPolicy)             // WARN at 80%, HARD_STOP at limit
    .governance(governance)                 // workflow governance engine
    .approvalGate(analysisGate)            // human-in-the-loop after analysis
    .memory(memory)                        // cross-run learning
    .build();
```

### MCP Tool Integration

```java
List<BaseTool> mcpTools = McpToolAdapter.fromServer("uvx", "mcp-server-fetch");
Agent researcher = Agent.builder()
    .role("Research Analyst")
    .tools(mcpTools)  // agent can now fetch live web content
    .build();
```

## Process Types

| Process | Use Case | How It Works |
|---------|----------|-------------|
| `SEQUENTIAL` | Pipeline workflows | Tasks run in dependency order. Each task gets prior outputs as context. |
| `HIERARCHICAL` | Coordinated teams | Manager agent plans, delegates to workers, synthesizes results. |
| `PARALLEL` | Independent research | Tasks without dependencies run concurrently. Dependency layers execute in sequence. |
| `ITERATIVE` | Quality-gated output | Tasks execute, reviewer evaluates against rubric, loop repeats with feedback until approved or max iterations. |
| `SELF_IMPROVING` | Dynamic adaptation | LLM plans the workflow, tasks evolve across iterations, reviewer prescribes NEXT_COMMANDS, CODE skills are generated/verified/hot-loaded, command ledger prevents redundancy, goal-driven stopping. |

## Examples

| Example | Process | What It Demonstrates |
|---------|---------|---------------------|
| **Stock Analysis** | PARALLEL | SEC filings + web search, 3 parallel agents + synthesis, tool health checks |
| **Due Diligence** | PARALLEL | Financial + News + Legal streams, auto-layering |
| **Research** | HIERARCHICAL | Manager + 4 specialists, task dependencies |
| **MCP Research** | SEQUENTIAL | Live web fetching via MCP protocol |
| **Iterative Memo** | ITERATIVE | Reviewer-driven refinement loop, 7-point quality rubric |
| **Self-Improving** | SELF_IMPROVING | Dynamic planning, CODE skill generation, task evolution, reviewer-driven commands, any query |
| **Penetration Testing** | SELF_IMPROVING | Network discovery → enumeration → exploitation with hydra/nikto/nmap, CODE skill generation |
| **Enterprise Governed** | SELF_IMPROVING | Self-improving + multi-tenancy + budgets + approval gates |
| **Codebase Analysis** | PARALLEL | Multi-agent code review with file/directory tools |
| **Web Research** | HIERARCHICAL | Manager + researchers + fact-checker |
| **Data Pipeline** | SEQUENTIAL | CSV analysis + transformation + reporting |

## Built-in Tool Library (21+ Tools)

All tools include routing metadata (triggerWhen/avoidWhen), categories, tags, and output schemas.

| Tool | Category | Description |
|------|----------|-------------|
| `web_search` | web | Multi-source web search (Google, Bing, NewsAPI) with financial data enrichment |
| `web_scrape` | web | URL content extraction with SSRF protection and hallucinated-URL detection |
| `http_request` | web | HTTP methods with headers, auth, and hallucinated-domain blocking |
| `shell_command` | computation | Whitelisted shell commands with timeout (nmap, hydra, nikto, enum4linux, smbclient, curl, and 50+ more) |
| `calculator` | computation | Mathematical expression evaluation |
| `code_execution` | computation | Sandboxed code execution (JavaScript, shell) |
| `file_read` | data-io | Read files with format detection (JSON, CSV, YAML, XML) |
| `file_write` | data-io | Write/append with directory creation and security checks |
| `directory_read` | data-io | List directory contents with glob patterns |
| `csv_analysis` | analysis | CSV parsing, statistics, filtering, aggregation |
| `json_transform` | data-io | JSONPath queries, flatten, CSV conversion |
| `xml_parse` | data-io | XPath queries on XML documents |
| `pdf_read` | data-io | PDF text extraction with page range support |
| `database_query` | data-io | SQL query execution |
| `sec_filings` | analysis | SEC EDGAR API for financial filings |
| `data_analysis` | analysis | General analytics and statistics |
| `semantic_search` | analysis | Vector/semantic search |
| `report_generator` | analysis | Structured report generation |
| `email` | communication | Email sending |
| `slack_webhook` | communication | Slack integration |
| `simulated_web_search` | web | Mock search for testing |

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
| Groovy | 4.x (skill sandbox) |
| Build | Maven |
| Tests | JUnit 5 + Mockito (643 tests) |
| Container | Docker + Docker Compose |

## Building

```bash
# Compile
./mvnw clean compile

# Run tests
./mvnw clean test
# Or via Docker:
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

SwarmAI brings multi-agent patterns to Java with Spring AI integration, quality-gated skill generation, enterprise governance, and token economics.
