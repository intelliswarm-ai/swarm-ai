# SwarmAI - Iterative Investment Memo Example

Demonstrates the **ITERATIVE** process type — a cyclic workflow where agents execute tasks, a reviewer evaluates the output against a quality rubric, and tasks re-execute with feedback until approved.

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                    ITERATION LOOP                            │
│                                                              │
│   [Research Analyst]  ──→  [Memo Writer]  ──→  [MD Reviewer] │
│       SEC + Web               Drafts memo       Reviews vs   │
│       tool evidence           with evidence     7-pt rubric  │
│                                                      │       │
│                                  ┌───────────────────┤       │
│                                  │                   │       │
│                           NEEDS_REFINEMENT      APPROVED     │
│                           + specific feedback        │       │
│                                  │                   ↓       │
│                                  └──→ loop back    DONE      │
└──────────────────────────────────────────────────────────────┘
```

### Agents

| Agent | Role | Tools | Temperature |
|-------|------|-------|-------------|
| **Research Analyst** | Extracts financial data from SEC filings + web | Calculator, WebSearch, SECFilings | 0.1 |
| **Memo Writer** | Drafts institutional-quality investment memo | Calculator | 0.3 |
| **Managing Director** (reviewer) | Reviews against 7-point quality rubric | — | 0.2 |

### 7-Point Quality Rubric

The MD reviewer grades on:

1. **Thesis Clarity** — Is BUY/HOLD/SELL stated clearly with justification?
2. **Data Grounding** — Does every number cite a source?
3. **Peer Comparison** — 3+ competitors on 3+ metrics in a table?
4. **Risk Analysis** — 5+ risks with likelihood, impact, mitigation?
5. **Catalyst Identification** — 3+ catalysts with dates and probabilities?
6. **Cross-Referencing** — Do sections reference each other?
7. **Completeness** — All 7 memo sections present and substantive?

All dimensions must score 4+/5 for **APPROVED**.

## Quick Start

### From Project Root (Recommended)

Uses the same `docker-compose.run.yml` as all other SwarmAI examples:

```bash
# 1. Configure your LLM provider (one-time setup)
cd /path/to/swarm-ai
cp .env.example .env
# Edit .env — add OPENAI_API_KEY, ANTHROPIC_API_KEY, or OLLAMA_BASE_URL

# 2. Run the iterative memo
docker compose -f docker-compose.run.yml run --rm iterative-memo NVDA 3
docker compose -f docker-compose.run.yml run --rm iterative-memo TSLA 5
docker compose -f docker-compose.run.yml run --rm iterative-memo AAPL 1  # single-pass, no refinement
```

### Standalone Docker (with Ollama)

Uses the example-specific `docker-compose.yml` with a self-contained Ollama instance:

```bash
cd examples/iterative

# Linux/Mac
./run-iterative.sh              # NVDA, 3 iterations
./run-iterative.sh TSLA 5       # Tesla, 5 iterations

# Windows PowerShell
.\run-iterative.ps1 AAPL 3

# Windows Batch
run-iterative.bat TSLA

# Or Docker Compose directly
STOCK_TICKER=TSLA MAX_ITERATIONS=5 docker compose up --build
```

## What to Watch For

The power of iterative refinement is visible in the logs. Look for:

```
ITERATION_STARTED   — Each review-refine cycle beginning
NEEDS_REFINEMENT    — Reviewer feedback listing specific issues
                      (e.g., "Section 3 has no peer comparison table")
APPROVED            — Reviewer satisfied; output meets quality bar
```

Typical run: iteration 1 gets rejected (missing tables, weak risk analysis), iteration 2 addresses most feedback, iteration 3 gets approved.

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `STOCK_TICKER` | `NVDA` | Stock ticker to analyze |
| `MAX_ITERATIONS` | `3` | Maximum review-refine cycles |
| `SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL` | `llama3.2:3b` | LLM model |
| `SPRING_AI_OLLAMA_CHAT_OPTIONS_TEMPERATURE` | `0.7` | Model temperature |

### Config File

See `config/iterative-config.yml` for agent temperatures, task timeouts, and review rubric settings.

## Directory Structure

```
examples/iterative/
├── Dockerfile              # Container image definition
├── docker-compose.yml      # Full orchestration (Ollama + app)
├── docker-entrypoint.sh    # Startup script with health checks
├── config/
│   └── iterative-config.yml  # Agent and task configuration
├── run-iterative.sh        # Linux/Mac quick run
├── run-iterative.bat       # Windows batch quick run
├── run-iterative.ps1       # Windows PowerShell quick run
├── build-docker.sh         # Docker image builder
├── .dockerignore           # Build context filter
└── README.md               # This file
```

## Troubleshooting

### Ollama not ready
The entrypoint script waits up to 5 minutes for Ollama. If it times out, ensure Docker has enough resources (4GB+ RAM recommended).

### Model not found
The `ollama-setup` service downloads `llama3.2:3b` automatically. If it fails, pull manually:
```bash
docker compose exec ollama ollama pull llama3.2:3b
```

### Out of memory
Iterative workflows accumulate context across iterations. If you see OOM errors, increase Java heap:
```yaml
environment:
  - JAVA_OPTS=-Xmx4g
```

### Max iterations reached without approval
This is normal with smaller models. Options:
- Increase `MAX_ITERATIONS` (cost/time tradeoff)
- Use a larger model (`llama3.1:8b` or an API-backed model)
- Relax the quality criteria in the workflow code
