# SwarmAI Framework

[![Website](https://img.shields.io/badge/website-intelliswarm.ai-blue.svg)](https://www.intelliswarm.ai)
[![Maven CI](https://github.com/intelliswarm-ai/swarm-ai/actions/workflows/maven-ci.yml/badge.svg)](https://github.com/intelliswarm-ai/swarm-ai/actions/workflows/maven-ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-blue.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.4](https://img.shields.io/badge/Spring%20Boot-3.4.4-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring AI 1.0](https://img.shields.io/badge/Spring%20AI-1.0.4%20GA-brightgreen.svg)](https://spring.io/projects/spring-ai)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Tests](https://img.shields.io/badge/tests-1128%20passing-brightgreen.svg)](#)

**The enterprise-grade, self-improving multi-agent orchestration framework for Java.** Built on Spring AI 1.0.4 GA and Spring Boot 3.4 with type-safe state management, dynamic skill generation, RL-powered decision making, and production-ready enterprise features.

**[www.intelliswarm.ai](https://www.intelliswarm.ai)** | [Documentation](#documentation) | [Quick Start](#quick-start) | [Migration Guide](docs/MIGRATION_GUIDE.md)

## The 10% Self-Improvement Investment

SwarmAI is the first agentic framework where **every workflow makes the framework better for everyone**.

Every SwarmAI workflow automatically reserves 10% of its token budget for framework-level self-improvement. This isn't per-workflow optimization -- it produces improvements that ship in the next release and benefit all users on upgrade. The same YAML workflow, unchanged, runs cheaper and better on every new version.

**How it works:**
- After your workflow completes, the 10% phase analyzes what happened -- failures, expensive tasks, convergence patterns, tool selection, skill effectiveness
- It extracts **generic** rules (never domain-specific) that apply across all workflow types
- These rules flow into intelligence artifacts: learned policy weights, convergence defaults, tool routing hints, anti-patterns, graduated skills
- Validated automatically by the full test suite. If tests pass, improvements ship in the next release

**What this means for users:**
- `v1.1.0`: Framework uses hardcoded defaults. Your workflow uses 95K tokens.
- `v1.2.0`: Learned from thousands of runs. Same workflow: 62K tokens, same quality.
- `v1.3.0`: Process auto-selected, anti-patterns caught at compile time. Same workflow: 41K tokens, better quality.

**The Community Investment Ledger** tracks the aggregate impact -- total tokens invested, improvements shipped, skills graduated, anti-patterns discovered, and ROI. This is the collective intelligence of every SwarmAI user, compounding with every release.

> *SwarmAI doesn't just run agents. It invests 10% of every execution into becoming a better framework -- and the 10% pays for itself within 5 runs.*

## What's New

- **Self-Improving Module** (`swarmai-self-improving`) -- 10% token budget reserve for automatic framework improvement. Community Investment Ledger for tracking collective impact. Three-tier improvement pipeline: automatic data updates, reviewed PRs, architecture proposals
- **Enterprise Module** (`swarmai-enterprise`) -- Commercial tier with license-gated multi-tenancy, advanced governance, and deep RL (DQN neural networks)
- **Self-Evaluation Module** (`swarmai-eval`) -- Framework for agentic self-evaluation, competitive benchmarks, and continuous quality improvement
- **Resilience** -- Circuit breaker + exponential backoff retry on LLM calls via resilience4j
- **Thread-Safe Observability** -- `ObservabilityContext` now propagates across parallel threads via `Snapshot` API
- **Typed Exception Hierarchy** -- `SwarmException`, `AgentExecutionException`, `ProcessExecutionException`, `ToolExecutionException`, `ConfigurationException`, `PermissionDeniedException`
- **Health Indicators** -- Spring Boot Actuator health checks for Memory, Budget, and EventStore subsystems
- **Flyway Migrations** -- Production-ready JDBC schema management for memory and audit tables
- **SPI Interfaces** -- `AuditSink`, `MeteringSink`, `LicenseProvider` for enterprise extensibility
- **Configuration Validation** -- Fail-fast startup validation for budget, observability, and tenant config

## Quick Start

```xml
<!-- Core framework -->
<dependency>
    <groupId>ai.intelliswarm</groupId>
    <artifactId>swarmai-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Optional: 24 built-in tools (web, PDF, CSV, shell, etc.) -->
<dependency>
    <groupId>ai.intelliswarm</groupId>
    <artifactId>swarmai-tools</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Optional: YAML DSL for declarative workflows -->
<dependency>
    <groupId>ai.intelliswarm</groupId>
    <artifactId>swarmai-dsl</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Optional: Enterprise features (multi-tenancy, governance, deep RL) -->
<dependency>
    <groupId>ai.intelliswarm</groupId>
    <artifactId>swarmai-enterprise</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

```java
Agent researcher = Agent.builder()
    .role("Research Analyst")
    .goal("Find accurate, up-to-date information")
    .backstory("Experienced researcher who verifies facts.")
    .chatClient(chatClient)
    .tool(webSearchTool)
    .permissionMode(PermissionLevel.READ_ONLY)
    .build();

Agent writer = Agent.builder()
    .role("Content Writer")
    .goal("Write clear, engaging reports")
    .backstory("Turns research into well-structured articles.")
    .chatClient(chatClient)
    .build();

Task research = Task.builder()
    .id("research").description("Research: {topic}")
    .agent(researcher).build();

Task report = Task.builder()
    .id("report").description("Write a report from findings")
    .agent(writer).dependsOn("research")
    .outputFormat(OutputFormat.MARKDOWN).build();

SwarmOutput result = Swarm.builder()
    .agents(List.of(researcher, writer))
    .tasks(List.of(research, report))
    .process(ProcessType.SEQUENTIAL)
    .build()
    .kickoff(Map.of("topic", "AI agents in enterprise"));
```

### YAML DSL (Zero-Code)

```yaml
swarm:
  process: SEQUENTIAL
  agents:
    researcher:
      role: "Research Analyst"
      goal: "Find accurate information"
      tools: [web-search]
      permissionMode: READ_ONLY
    writer:
      role: "Content Writer"
      goal: "Write clear reports"
  tasks:
    research:
      description: "Research: {{topic}}"
      agent: researcher
    report:
      description: "Write a report from findings"
      agent: writer
      dependsOn: [research]
      outputFormat: MARKDOWN
```

```java
Swarm swarm = swarmLoader.load("workflows/research.yaml", Map.of("topic", "AI agents"));
SwarmOutput result = swarm.kickoff(Map.of());
```

### Build & Test

```bash
./mvnw clean test       # 1128 tests, all passing
./mvnw clean install    # install to local Maven repo
```

## Project Structure

```
swarm-ai/                        (parent POM, 11 modules)
├── swarmai-core/                Core: agents, tasks, 8 process types, state, skills, memory,
│                                knowledge, budget, governance, observability, resilience
├── swarmai-tools/               25 built-in tools (web, file, shell, PDF, CSV, security, etc.)
├── swarmai-dsl/                 YAML DSL parser & compiler (38 definition types)
├── swarmai-rl/                  Lightweight RL (contextual bandits, Thompson sampling)
├── swarmai-enterprise/          Enterprise: multi-tenancy, deep RL (DQN), advanced governance
├── swarmai-self-improving/      10% token budget self-improvement pipeline & reporting
├── swarmai-distributed/         RAFT consensus, distributed goals, intelligence mesh
├── swarmai-eval/                Self-evaluation swarm & competitive benchmarks
├── swarmai-studio/              Web dashboard for workflow monitoring
├── swarmai-bom/                 Bill of Materials for version alignment
└── docker/                      Dockerfiles and docker-compose configs
```

## Key Features

### 8 Process Types

| Process | Description |
|---------|-------------|
| `SEQUENTIAL` | Tasks in dependency order, each receives prior outputs |
| `PARALLEL` | Independent tasks run concurrently in layers |
| `HIERARCHICAL` | Manager delegates to workers, synthesizes results |
| `ITERATIVE` | Execute -> review -> refine loop until approved |
| `SELF_IMPROVING` | Iterative + dynamic skill generation for capability gaps |
| `SWARM` | Distributed fan-out with parallel self-improving agents |
| `DISTRIBUTED` | RAFT consensus, declarative goals, work partitioning, fault-tolerant coordination |
| `COMPOSITE` | Chain processes into pipelines |

### Enterprise Features

| Feature | Module | Description |
|---------|--------|-------------|
| **Multi-Tenancy** | enterprise | Tenant-isolated memory, knowledge, quotas, budgets |
| **Governance Gates** | core + enterprise | Human-in-the-loop approval checkpoints (BEFORE_TASK, AFTER_TASK) |
| **Budget Tracking** | core | Real-time token/cost tracking with HARD_STOP or WARN enforcement |
| **Deep RL** | enterprise | DQN neural network policy for self-improving workflow decisions |
| **License Management** | enterprise | JWT/RSA license validation, feature-gated bean activation |
| **Tool Permissions** | core | READ_ONLY, WORKSPACE_WRITE, DANGEROUS levels with WARN logging |
| **Tool Hooks** | core | Audit, sanitize, rate-limit, deny interceptors on every tool call |
| **Circuit Breaker** | core | resilience4j circuit breaker + retry on LLM API calls |
| **Health Checks** | core | Spring Boot Actuator indicators for Memory, Budget, EventStore |
| **Observability** | core | Correlation IDs, structured logging, decision tracing, event replay |

### SPI Extensibility

Core defines extension points that enterprise (or custom) modules implement:

| SPI | Purpose |
|-----|---------|
| `AuditSink` | Persistent audit trail (JDBC, Elasticsearch) |
| `MeteringSink` | Billing/metering hooks (Stripe, custom) |
| `LicenseProvider` | License validation (community default always valid) |
| `BudgetTracker` | Token/cost tracking (in-memory default) |
| `ApprovalGateHandler` | Approval workflow (in-memory default) |
| `TenantQuotaEnforcer` | Per-tenant resource limits |
| `PolicyEngine` | RL policy for self-improving decisions |

### Typed Exception Hierarchy

Production debugging with structured context on every exception:

```
SwarmException (base, carries swarmId + correlationId)
├── AgentExecutionException (agentId, taskId)
├── ProcessExecutionException (processType)
├── ToolExecutionException (toolName)
├── ConfigurationException (property)
└── PermissionDeniedException (toolName, required, agentLevel)
```

## Built-in Tools (24)

| Category | Tools |
|----------|-------|
| Web | `web_search` `web_scrape` `http_request` `headless_browser` |
| File I/O | `file_read` `file_write` `directory_read` `pdf_read` |
| Data | `csv_analysis` `json_transform` `xml_parse` `database_query` `data_analysis` |
| Compute | `calculator` `code_execution` `shell_command` |
| Communication | `email` `slack_webhook` |
| Specialized | `sec_filings` `report_generator` `semantic_search` |
| Adapters | `mcp_adapter` (Model Context Protocol) |

## Tech Stack

| Component | Version |
|-----------|---------|
| Java | 21 |
| Spring Boot | 3.4.4 |
| Spring AI | 1.0.4 (GA) |
| resilience4j | 2.2.0 |
| MCP Java SDK | 0.10.0 |
| DJL (Deep Java Library) | 0.29.0 |
| Groovy | 4.x (skill sandbox) |
| Build | Maven (11 modules) |
| Tests | JUnit 5 + Mockito (1128 tests) |

## Documentation

- **[Getting Started Guide](GETTING_STARTED.md)** -- Comprehensive tutorial with full code examples
- **[API Keys Setup](docs/API_KEYS_SETUP_GUIDE.md)** -- Configure LLM provider API keys
- **[Docker Guide](docs/DOCKER_EXAMPLE_GUIDE.md)** -- Run SwarmAI in Docker
- **[Self-Improving Workflows](docs/SELF_IMPROVING_WORKFLOWS.md)** -- Skill generation deep dive
- **[YAML DSL Guide](GETTING_STARTED.md#yaml-dsl)** -- Define workflows in YAML

## License

Core modules (swarmai-core, swarmai-tools, swarmai-dsl, swarmai-rl): **MIT License**

Enterprise module (swarmai-enterprise): **BSL 1.1** (source-available, production use requires commercial license; converts to MIT after 4 years)

See [LICENSE](LICENSE) for details.
