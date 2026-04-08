# Contributing to SwarmAI

Thank you for your interest in contributing to SwarmAI! This guide covers everything you need to get started.

## Quick Start

```bash
# Clone the repository
git clone https://github.com/intelliswarm-ai/swarm-ai.git
cd swarm-ai

# Build all modules
./mvnw clean install

# Run tests (1,013 tests across 9 modules)
./mvnw test
```

**Requirements:** Java 21+, Maven 3.9+

## Project Structure

```
swarm-ai/
├── swarmai-core/          Core framework (agents, tasks, processes, state, skills)
├── swarmai-tools/         24 built-in tools (web, file, PDF, CSV, shell)
├── swarmai-dsl/           YAML DSL parser & compiler
├── swarmai-enterprise/    Enterprise features (tenancy, governance, RBAC, audit)
├── swarmai-eval/          Self-evaluation swarm & benchmarks
├── swarmai-studio/        Web dashboard
├── swarmai-bom/           Bill of Materials
└── docs/                  Documentation
```

## How to Contribute

### Reporting Bugs

Open an issue using the **Bug Report** template. Include:
- Steps to reproduce
- Expected vs actual behavior
- Java version, Spring Boot version, OS
- Relevant logs or stack traces

### Suggesting Features

Open an issue using the **Feature Request** template. Describe:
- The problem you're trying to solve
- Your proposed solution
- Alternatives you've considered

### Submitting Code

1. **Fork** the repository and create a feature branch from `main`
2. **Write tests** for your changes (we require tests for all new code)
3. **Run the full test suite:** `./mvnw clean test`
4. **Ensure no regressions:** all 1,013+ tests must pass
5. **Submit a PR** using the PR template

### Good First Issues

Look for issues labeled `good-first-issue`. These are scoped, well-documented tasks suitable for newcomers.

## Code Guidelines

### Style
- Java 21 features welcome (records, sealed classes, pattern matching)
- Follow existing patterns in the codebase
- Use SLF4J for logging (`logger.info`, `logger.warn`, `logger.error`)
- Prefer immutable types (records, unmodifiable collections)
- No Lombok -- we use records and builders instead

### Architecture
- **Public API** classes are annotated with `@PublicApi` -- don't break their signatures
- **Internal** classes are annotated with `@InternalApi` -- these can change freely
- **SPI interfaces** in `swarmai-core/spi/` are extension points for enterprise implementations
- **Enterprise features** go in `swarmai-enterprise`, not `swarmai-core`

### Testing
- Unit tests: JUnit 5 + Mockito
- Test class naming: `{ClassName}Test` with `@Nested` inner classes for grouping
- All tests must be deterministic -- no flaky tests, no network calls in unit tests
- Run `./mvnw test` before submitting

### Commit Messages
- Use conventional commits: `feat:`, `fix:`, `docs:`, `refactor:`, `test:`
- Keep the subject line under 72 characters
- Reference issues: `feat(DSL): add conditional task support (#42)`

## Module-Specific Guidelines

### swarmai-core
- This is the public API surface. Changes here affect all consumers.
- Add `@PublicApi` to new public interfaces/classes
- Ensure backward compatibility or document breaking changes

### swarmai-enterprise
- All enterprise features must be gated behind `LicenseManager`
- Auto-configuration must use `@ConditionalOnBean(LicenseManager.class)`
- Never import enterprise classes from core

### swarmai-eval
- New eval scenarios go in `scenario/` package
- Each scenario must implement `EvalScenario` interface
- Scenarios must not require an LLM API key to pass

### swarmai-tools
- Each tool extends `BaseTool`
- Include SSRF protection for network tools
- Test with both valid and malicious inputs

## Running the Self-Evaluation

```bash
# Run the eval swarm (checks framework health)
mvn exec:java -pl swarmai-eval \
  -Dexec.mainClass="ai.intelliswarm.swarmai.eval.EvalSwarmRunner"
```

The eval produces a Framework Value Score (0-100). Score must be >= 70 for release.

## Release Gate

Before any release, all gates must pass:

```bash
# Run the full release gate
./mvnw verify -Prelease-gate
```

This runs: SpotBugs, Maven Enforcer, OWASP dependency check, JaCoCo coverage, and all tests.

## License

By contributing, you agree that your contributions will be licensed under the MIT License (for core modules) or BSL 1.1 (for enterprise module).

## Questions?

- Open a GitHub Discussion
- Check existing issues for similar questions
- Read the [Getting Started Guide](GETTING_STARTED.md) for framework documentation
