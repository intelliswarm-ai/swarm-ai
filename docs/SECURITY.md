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

## The Groovy skill sandbox

LLM-generated `CODE` skills are Groovy, compiled and run **in your JVM**. The sandbox is
`GroovySkillSandbox`, and the same hardened `CompilerConfiguration` is applied at every
compile site — skill execution, unit-test execution, assertion execution, and validation —
so code cannot be validated under one policy and executed under another.

What it enforces, at the AST level:

- **Class allowlist, not a denylist.** `setIndirectImportCheckEnabled(true)` means every
  class the code touches must be allowlisted, whether it is imported, fully qualified, or
  auto-imported. A denylist of import statements is not a boundary: Groovy reaches
  `java.io.File` or `Runtime` with no import at all.
- **`java.lang` is not blanket-allowed** — that would re-admit `Runtime`, `ProcessBuilder`,
  `System`, `Thread`, `Class` and `ClassLoader`. Only value and utility types are allowed.
- **Reflective escape hatches are blocked**: `.class` / `.metaClass` / `.classLoader`
  property access, `getClass()`, `forName()`, `newInstance()`, `setAccessible()`, method
  pointers (`obj.&m`), and dynamically-named calls (`obj."${name}"()`), which would
  otherwise let generated code assemble a blocked method name at runtime.
- **`execute()` is receiver-checked**: Groovy's `String.execute()` spawns a shell, so
  `execute()` is permitted only when its receiver chain is rooted at the `tools` binding —
  the documented `tools.<name>.execute(...)` calling convention keeps working, while
  `'sh -c ...'.execute()` is refused.

Blocked code is refused at compile time; `GeneratedSkill.execute()` reports this as an
`Error: Skill execution failed: ...` result rather than throwing, so one bad skill degrades
a turn instead of killing the swarm.

**Limits — read before trusting this with hostile input.** This is a compile-time sandbox in
a shared JVM. It stops the reachable-API classes of attack above; it does **not** stop
resource exhaustion (CPU/heap), and it is not a substitute for OS-level isolation. Skill
execution timeouts are enforced by the caller, not the sandbox. If your generated skills
process untrusted input, or you need a hard boundary, use the `CONTAINER` skill type, which
runs out-of-process with no `--privileged` and no `USER root`.

Vulnerabilities in the sandbox are in scope for the reporting process above; the adversarial
cases we test are in `SkillValidatorTest` and `GeneratedSkillSandboxExecutionTest`.

## Memory and tenant isolation

`Memory.searchByAgentPrefix` fails closed: a custom `Memory` implementation that does not
override it returns no results and logs a warning, rather than falling back to an unscoped
search. This matters because `TenantAwareMemory` relies on prefix scoping for tenant
isolation — a silent fallback would leak one tenant's memories to another.
