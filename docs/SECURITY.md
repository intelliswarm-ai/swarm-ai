# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability in SwarmAI, please report it responsibly.

**Do not open a public GitHub issue for security vulnerabilities.**

Instead, please email security concerns to the maintainers or use [GitHub's private vulnerability reporting](https://github.com/intelliswarm-ai/swarm-ai/security/advisories/new).

## Supported Versions

| Version | Supported |
|---------|-----------|
| 1.x     | Yes       |

## Security Considerations

SwarmAI includes tools that execute code, make HTTP requests, and interact with external systems. When deploying:

- Never expose agent APIs to untrusted users without authentication
- Review tool permissions before enabling `ShellCommandTool` or `CodeExecutionTool`
- Use environment variables for API keys, never hardcode them
- Enable budget tracking to prevent runaway LLM costs
- Use governance approval gates for sensitive operations
