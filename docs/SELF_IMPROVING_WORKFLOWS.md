# SwarmAI — Self-Improving Agentic Workflows

## The Vision

**If building agentic frameworks was 2024–2025, self-improving agents are 2026.**

OpenClaw — the fastest-growing open-source project in GitHub history (9K → 210K stars in weeks) — proved one thing: users want agents that **write their own skills**. Its Foundry extension observes your workflow patterns and, at 5+ uses with 70%+ success rate, crystallizes them into permanent tools. The system that writes code IS the code being written.

SwarmAI's killer feature: **workflows that self-improve by discovering capability gaps, generating new tools on the fly, validating them, and iterating until the goal is achieved** — all within a single workflow execution.

---

## How It Works

```
┌──────────────────────────────────────────────────────────────┐
│                   SELF-IMPROVING WORKFLOW                    │
│                                                              │
│  ┌──────────┐    ┌──────────────┐    ┌────────────────┐      │
│  │ Execute  │───▶│ Evaluate     │───▶│ Gap Detected? │      │
│  │ Tasks    │    │ Results      │    │                │      │
│  └──────────┘    └──────────────┘    └───────┬────────┘      │
│       ▲                                      │               │
│       │                               YES    │    NO         │
│       │                                ▼     │    ▼          │
│       │                          ┌──────────┐│ ┌──────┐      │
│       │                          │ Generate ││ │ DONE │      │
│       │                          │ New Skill││ └──────┘      │
│       │                          └────┬─────┘│               │
│       │                               │      │               │
│       │                          ┌────▼─────┐│               │
│       │                          │ Validate ││               │
│       │                          │ & Test   ││               │
│       │                          └────┬─────┘│               │
│       │                               │      │               │
│       │                          ┌────▼─────┐│               │
│       │                          │ Register ││               │
│       └──────────────────────────│ Skill    ││               │
│                                  └──────────┘│               │
└──────────────────────────────────────────────────────────────┘
```

### The Loop

1. **Execute** — Agents run their tasks using available tools
2. **Evaluate** — A reviewer agent scores the output against quality criteria
3. **Gap Detection** — If the output is insufficient, the reviewer identifies WHAT is missing (not just "try harder" — specific capability gaps like "no tool to parse XBRL data" or "need a date comparison function")
4. **Skill Generation** — A builder agent writes a new tool: code, description, parameter schema, and test cases
5. **Validation** — The generated tool is tested in a sandbox. If it fails, the builder refines it
6. **Registration** — The validated tool is added to the agent's toolkit at runtime
7. **Re-execute** — The workflow re-runs with the new capability. Repeat until approved

---

## Competitive Landscape

| System | Self-Improvement Mechanism | Scope |
|--------|--------------------------|-------|
| **OpenClaw Foundry** | Observes patterns → crystallizes into skills at 5+ uses / 70% success | Personal assistant (local) |
| **Voyager (NVIDIA)** | GPT-4 writes game skills → stores in library → retrieves by embedding similarity | Minecraft (research) |
| **AutoGPT** | Plugin marketplace, manual installation | Task automation |
| **Reflexion** | Verbal self-reflection → retry with linguistic feedback | Single-task improvement |
| **Self-Refine** | Generate → self-critique → refine loop | Output quality |
| **SwarmAI (proposed)** | Multi-agent workflow generates tools → validates → registers → re-executes | Enterprise workflows |

**SwarmAI's unique angle:** Self-improvement within a structured multi-agent workflow (not just a single agent), with institutional-grade safety (sandboxing, validation, approval gates) and cost tracking.

---

## Architecture

### New Components

#### 1. SkillRegistry — Dynamic Tool Storage & Discovery

A persistent registry where generated tools are stored, indexed, and retrieved.

```java
public interface SkillRegistry {
    // Store a dynamically generated skill
    void register(GeneratedSkill skill);

    // Find skills by semantic similarity to a task description
    List<GeneratedSkill> search(String taskDescription, int limit);

    // Get all skills for a domain
    List<GeneratedSkill> getByDomain(String domain);

    // Track usage and effectiveness
    void recordUsage(String skillId, boolean success, long executionTimeMs);

    // Promote a skill to permanent (after threshold met)
    void promote(String skillId);
}
```

**GeneratedSkill** contains:
- `name`, `description`, `domain` — For discovery
- `code` — The tool implementation (JavaScript or Groovy)
- `parameterSchema` — JSON Schema for inputs
- `testCases` — Auto-generated validation tests
- `effectiveness` — Success rate from usage tracking
- `usageCount` — How many times it's been invoked
- `status` — CANDIDATE → VALIDATED → PROMOTED → PERMANENT

#### 2. SkillGenerator — LLM-Powered Tool Creation

An agent that writes new tools based on identified capability gaps.

```java
public class SkillGenerator {
    // Given a gap description, generate a new tool
    GeneratedSkill generate(String gapDescription, List<GeneratedSkill> existingSkills);

    // Refine a skill based on test failure feedback
    GeneratedSkill refine(GeneratedSkill failed, String errorMessage);
}
```

**Generation prompt includes:**
- The capability gap description from the reviewer
- Existing tool descriptions (to avoid duplicates)
- The BaseTool interface contract
- Test case requirements
- Safety constraints (no network, no filesystem writes, no system commands)

#### 3. SkillValidator — Sandbox Testing

Validates generated tools before they're registered.

```java
public class SkillValidator {
    // Run generated test cases in sandbox
    ValidationResult validate(GeneratedSkill skill);

    // Static analysis for safety issues
    SecurityScanResult securityScan(GeneratedSkill skill);
}
```

**Validation pipeline:**
1. **Parse check** — Is the code syntactically valid?
2. **Security scan** — No blocked commands, no network access, no path traversal
3. **Test execution** — Run auto-generated test cases in sandboxed CodeExecutionTool
4. **Schema validation** — Does the parameter schema match the implementation?
5. **Description quality** — Is the description clear enough for LLM discovery?

#### 4. SelfImprovingProcess — The Orchestrator

A new `ProcessType.SELF_IMPROVING` that extends `IterativeProcess` with skill generation.

```java
public class SelfImprovingProcess implements Process {
    // Execute workflow with dynamic skill acquisition
    SwarmOutput execute(List<Task> tasks, Map<String, Object> inputs, String swarmId) {
        int iteration = 0;
        while (iteration < maxIterations && !approved) {
            // 1. Execute tasks with current tools
            List<TaskOutput> outputs = executeTasks(tasks, currentTools);

            // 2. Reviewer evaluates
            ReviewResult review = reviewer.evaluate(outputs, qualityCriteria);

            if (review.isApproved()) {
                approved = true;
            } else if (review.hasCapabilityGaps()) {
                // 3. Generate new skills for identified gaps
                for (String gap : review.getCapabilityGaps()) {
                    GeneratedSkill skill = skillGenerator.generate(gap, existingSkills);

                    // 4. Validate in sandbox
                    ValidationResult validation = validator.validate(skill);
                    if (validation.passed()) {
                        // 5. Register and make available
                        skillRegistry.register(skill);
                        currentTools.add(skill.toBaseTool());
                        log("New skill registered: " + skill.getName());
                    } else {
                        // 5b. Refine and retry
                        skill = skillGenerator.refine(skill, validation.errors());
                        // ... retry validation
                    }
                }

                // 6. Reset tasks and re-execute with new tools
                tasks.forEach(Task::reset);
            } else {
                // Regular feedback — no new skills needed, just improve output
                injectFeedback(review.getFeedback());
                tasks.forEach(Task::reset);
            }

            iteration++;
        }
    }
}
```

---

## The Reviewer's Role: Gap Detection

The critical innovation is that the reviewer doesn't just say "NEEDS_REFINEMENT" — it specifically identifies **capability gaps** vs. **quality issues**:

```
REVIEW OUTPUT:

STATUS: NEEDS_REFINEMENT

QUALITY_ISSUES:
- Executive summary lacks specific revenue figures
- Risk matrix missing probability percentages

CAPABILITY_GAPS:
- NO_TOOL: Need a date comparison tool to calculate year-over-year growth rates
- NO_TOOL: Need a currency conversion tool to normalize international revenue
- INSUFFICIENT_TOOL: WebSearchTool returns no results without API keys —
  need a tool that can scrape financial data from Yahoo Finance directly
```

The reviewer agent is prompted to distinguish:
- **QUALITY_ISSUES** → Pass feedback to worker agents (existing iterative behavior)
- **CAPABILITY_GAPS** → Trigger skill generation pipeline

---

## Skill Lifecycle

```
CANDIDATE ──▶ VALIDATED ──▶ ACTIVE ──▶ PROMOTED ──▶ PERMANENT
   │              │            │           │             │
   │ Generated    │ Passed     │ Used in   │ 5+ uses    │ Persisted
   │ by LLM      │ sandbox    │ workflow  │ 70%+ success│ to disk
   │              │ tests      │           │             │
   ▼              ▼            ▼           ▼             ▼
  (discard      (discard     (track      (save to     (available
   if fails)     if unsafe)   usage)     registry)     permanently)
```

### Crystallization Threshold (inspired by OpenClaw Foundry)

A skill is promoted to PERMANENT when:
- **Usage count ≥ 5** — Used across multiple workflow executions
- **Success rate ≥ 70%** — Actually helps more than it hurts
- **No security violations** — Never triggered a safety alert

Permanent skills are serialized to disk and loaded on next application startup.

---

## Skill Storage Format

Skills are stored as **SKILL.md** files (compatible with the emerging Agent Skills standard used by Spring AI, AgentScope, and Claude):

```markdown
---
name: yahoo_finance_scraper
description: Scrapes financial data (price, market cap, P/E ratio) from Yahoo Finance for a given ticker symbol.
domain: finance
version: 1.0
created: 2026-03-26T12:00:00
effectiveness: 0.85
usage_count: 12
status: PROMOTED
---

## Instructions

Fetch financial data from Yahoo Finance for the given ticker symbol.
Returns a structured summary with key metrics.

## Parameters

- `ticker` (string, required): Stock ticker symbol (e.g., AAPL, MSFT)
- `metrics` (array, optional): Specific metrics to extract (default: all)

## Implementation

```javascript
function execute(params) {
    // Fetch Yahoo Finance page
    var url = "https://finance.yahoo.com/quote/" + params.ticker;
    // ... scraping logic
    return {
        ticker: params.ticker,
        price: extractedPrice,
        marketCap: extractedMarketCap,
        peRatio: extractedPERatio
    };
}
```

## Test Cases

```javascript
// Test 1: Valid ticker
assert(execute({ticker: "AAPL"}).price > 0);

// Test 2: Invalid ticker returns error
assert(execute({ticker: "INVALID_XYZ"}).error != null);
```
```

---

## Safety Architecture

### Defense in Depth

| Layer | Mechanism | What It Prevents |
|-------|-----------|-----------------|
| **Input** | Gap description sanitization | Prompt injection into skill generator |
| **Generation** | Constrained code templates | Arbitrary code generation |
| **Static Analysis** | Blocked patterns scan | Network access, file writes, system commands |
| **Sandbox** | CodeExecutionTool (JavaScript engine, no I/O) | Runtime exploits |
| **Validation** | Auto-generated test cases must pass | Broken skills entering workflow |
| **Approval Gate** | Optional human review for PROMOTED skills | Malicious skills persisting |
| **Monitoring** | Usage tracking + effectiveness scoring | Bad skills getting promoted |
| **Rollback** | Skill versioning + disable mechanism | Reverting broken skills |

### Skill Code Restrictions

Generated skills are executed via `CodeExecutionTool` (JavaScript engine), which:
- Has NO filesystem access
- Has NO network access
- Has timeout enforcement (30s max)
- Has output length limits (8000 chars)
- Runs in-process (no shell, no subprocesses)

For skills that NEED external access (web scraping, API calls), they must be composed from existing validated tools:
```
Generated Skill = Orchestration Logic + Existing Tools
                  (new code)           (WebScrapeTool, HttpRequestTool, etc.)
```

The generated code orchestrates existing tools rather than implementing raw I/O — similar to how CrewAI tasks compose tools.

---

## Integration with Existing SwarmAI Components

### What Already Exists (Ready to Leverage)

| Component | Role in Self-Improvement |
|-----------|------------------------|
| **IterativeProcess** | Feedback loop: execute → review → refine → repeat |
| **Memory** | Store learned patterns, skill metadata, effectiveness scores |
| **Knowledge** | Store domain knowledge that informs skill generation |
| **CodeExecutionTool** | Sandbox for testing generated skill code |
| **Agent.tools** | Runtime tool list (needs `addTool()` method) |
| **Task.reset()** | Re-execute tasks with new capabilities |
| **Token tracking** | Cost-conscious iteration (self-improvement is expensive) |
| **Anti-hallucination guardrails** | Prevent fabricated skill claims |

### What Needs to Be Built

| Component | Effort | Description |
|-----------|--------|-------------|
| **SkillRegistry** | Medium | Persistent storage for generated skills with search/versioning |
| **SkillGenerator** | Medium | LLM-powered tool code generation with prompts and templates |
| **SkillValidator** | Medium | Sandbox testing + security scanning pipeline |
| **SelfImprovingProcess** | High | New ProcessType extending IterativeProcess |
| **Agent.addTool()** | Low | Runtime tool registration method on Agent |
| **Reviewer gap detection** | Low | Enhanced reviewer prompt for CAPABILITY_GAP identification |
| **SKILL.md loader** | Medium | Load persisted skills on startup |
| **Skill promotion logic** | Low | Usage tracking + threshold-based promotion |

---

## Example: Self-Improving Stock Analysis

### Iteration 1 — Standard Execution
```
Agent: Analyze AAPL financials
Tools: [WebSearchTool, SECFilingsTool, CalculatorTool]
Result: Revenue data found, but can't calculate YoY growth properly
Reviewer: CAPABILITY_GAP — need a percentage_change calculator tool
```

### Iteration 2 — Skill Generated
```
SkillGenerator creates: percentage_change_tool
  - Input: {current: number, previous: number}
  - Output: percentage change with direction
  - Tests: pass in sandbox

Agent re-executes with: [WebSearchTool, SECFilingsTool, CalculatorTool, percentage_change_tool]
Result: YoY growth calculated, but currency mismatch in international comparisons
Reviewer: CAPABILITY_GAP — need currency normalization
```

### Iteration 3 — Another Skill Generated
```
SkillGenerator creates: currency_normalizer
  - Uses HttpRequestTool internally to fetch exchange rates
  - Normalizes all values to USD
  - Tests: pass in sandbox

Agent re-executes with expanded toolkit
Result: Comprehensive analysis with normalized financials
Reviewer: APPROVED
```

### Result
- Started with 3 tools, ended with 5
- 2 new tools generated, validated, and used — all within one workflow
- New tools saved to SkillRegistry for future workflows
- Next time a similar analysis runs, skills are pre-loaded

---

## Why This Is a Killer Feature

### vs. Static Frameworks (CrewAI, LangChain, AutoGen)
These frameworks require you to **pre-build** every tool an agent might need. If a workflow encounters a capability gap, it fails or produces "DATA NOT AVAILABLE."

SwarmAI fills gaps **during execution** — the workflow adapts to what it discovers it needs.

### vs. OpenClaw Foundry
Foundry is a **personal assistant** that improves over many sessions. SwarmAI applies the same principle to **enterprise workflows** — with multi-agent coordination, safety gates, cost tracking, and structured process types.

### vs. Voyager
Voyager proved the concept in a game environment. SwarmAI brings it to **production business workflows** — financial analysis, due diligence, competitive research — where generated skills must be safe, auditable, and cost-effective.

### The Moat
No other Java agentic framework has this. Combined with SwarmAI's existing differentiators (anti-hallucination guardrails, token economics, CrewAI-style orchestration), self-improving workflows create a **compound advantage** that's hard to replicate.

---

## Implementation Roadmap

| Phase | What | Effort | Enables |
|-------|------|--------|---------|
| **1** | `Agent.addTool()` + enhanced reviewer prompt for gap detection | 1-2 days | Runtime tool addition, gap identification |
| **2** | `SkillGenerator` + `SkillValidator` (sandbox testing) | 3-5 days | LLM generates and validates new tools |
| **3** | `SelfImprovingProcess` (new ProcessType) | 3-5 days | Full self-improving workflow loop |
| **4** | `SkillRegistry` + SKILL.md persistence + promotion logic | 2-3 days | Cross-session skill persistence |
| **5** | Usage tracking, effectiveness scoring, crystallization | 2-3 days | Skills get better over time |
| **6** | Safety hardening, approval gates, audit logging | 2-3 days | Enterprise readiness |

**Total estimated effort: 2-3 weeks for a production-ready implementation.**

---

## Key Insight

> The breakthrough isn't that agents can write code — GPT-4 and Claude already do that. The breakthrough is **closing the loop**: the agent writes a tool, validates it, uses it, measures its effectiveness, and either promotes or discards it — all within the workflow execution. The workflow doesn't just produce output. It produces better versions of itself.
