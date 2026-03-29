# Contributing to SwarmAI

Thank you for your interest in contributing to SwarmAI!

## How to Contribute

1. **Fork** the repository
2. **Create a branch** for your feature or fix (`git checkout -b feature/my-feature`)
3. **Make your changes** and write tests
4. **Run tests** to ensure everything passes: `mvn clean test`
5. **Commit** with a clear message
6. **Open a Pull Request** against `main`

## Development Setup

### Prerequisites
- Java 21+
- Maven 3.9+

### Build
```bash
mvn clean install
```

### Run Tests
```bash
# Unit tests only
mvn test

# Including integration tests
mvn verify -Dgroups=integration
```

## Project Structure

```
swarm-ai/
  swarmai-core/     Core framework (agents, tasks, processes, state)
  swarmai-tools/    Built-in tool implementations
  swarmai-studio/   Optional web dashboard
  swarmai-bom/      Bill of Materials for version alignment
```

## Code Style

- Java 21 features are encouraged (records, sealed classes, pattern matching)
- Follow existing patterns in the codebase
- Write tests for new functionality
- Keep commits focused and atomic

## Reporting Issues

- Use [GitHub Issues](https://github.com/intelliswarm-ai/swarm-ai/issues)
- Include steps to reproduce, expected vs actual behavior
- Include Java version and OS

## License

By contributing, you agree that your contributions will be licensed under the [MIT License](../LICENSE).
