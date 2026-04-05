# Migration Guide: Adopting SwarmAI

Migrate your existing AI agent application to the SwarmAI self-improving framework. This guide covers migrations from Quarkus/LangChain4j, Spring AI standalone, LangGraph4J, and custom agent implementations.

---

## Why Migrate

Your current framework executes workflows and discards everything it learned. SwarmAI invests 10% of every workflow's token budget into framework-level improvements that ship in the next release. Same workflow, unchanged — runs cheaper and better on every upgrade.

---

## Quick Reference

| Source Framework | Effort | Key Changes |
|---|---|---|
| Quarkus + LangChain4j | Medium | Framework swap + CDI → Spring |
| Spring Boot + custom agents | Low | Add SwarmAI deps + YAML workflow |
| Spring AI standalone | Low | Add orchestration layer |
| LangGraph4J | Low | Replace graph with SwarmAI process types |
| Python (LangGraph/CrewAI) | High | Language migration (Java 21 required) |

---

## Migration from Quarkus + LangChain4j

This is the migration path demonstrated by the VulnPatcher project.

### Step 1: Replace pom.xml dependencies

**Remove:**
```xml
<!-- Remove all Quarkus dependencies -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-*</artifactId>
</dependency>

<!-- Remove LangChain4j -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-*</artifactId>
</dependency>
```

**Add:**
```xml
<!-- Spring Boot parent -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.4.4</version>
</parent>

<properties>
    <java.version>21</java.version>
    <swarmai.version>1.0.0-SNAPSHOT</swarmai.version>
</properties>

<!-- SwarmAI Framework -->
<dependency>
    <groupId>ai.intelliswarm</groupId>
    <artifactId>swarmai-core</artifactId>
    <version>${swarmai.version}</version>
</dependency>
<dependency>
    <groupId>ai.intelliswarm</groupId>
    <artifactId>swarmai-dsl</artifactId>
    <version>${swarmai.version}</version>
</dependency>
<dependency>
    <groupId>ai.intelliswarm</groupId>
    <artifactId>swarmai-self-improving</artifactId>
    <version>${swarmai.version}</version>
</dependency>

<!-- Spring Boot starters -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

### Step 2: Migrate annotations

| Quarkus (CDI) | Spring |
|---|---|
| `@ApplicationScoped` | `@Component` or `@Service` |
| `@Inject` | `@Autowired` or constructor injection |
| `@ConfigProperty(name = "x", defaultValue = "y")` | `@Value("${x:y}")` |
| `@QuarkusMain` | `@SpringBootApplication` |
| `@Provider` (JAX-RS ExceptionMapper) | `@RestControllerAdvice` |
| `@Path("/api")` | `@RestController` + `@RequestMapping("/api")` |
| `@GET`, `@POST` | `@GetMapping`, `@PostMapping` |

### Step 3: Remove reactive wrappers (optional)

If using Mutiny:

```java
// Before (Quarkus Mutiny)
public Uni<List<Vulnerability>> fetchVulnerabilities() {
    return Uni.createFrom().item(() -> { ... });
}

// After (synchronous — simpler)
public List<Vulnerability> fetchVulnerabilities() {
    return List.of(...);
}
```

### Step 4: Replace custom agent system with YAML workflow

**Before** — Custom agents with LangChain4j:
```java
@ApplicationScoped
public class SecurityEngineerAgent {
    @Inject @CodeGeneration OllamaChatModel model;

    public String generateSecureFix(String code, String vuln, String lang) {
        return model.generate("Fix this vulnerability: " + vuln + "\n" + code);
    }
}

@ApplicationScoped
public class LLMOrchestrator {
    @Inject SecurityEngineerAgent engineer;
    @Inject SecLeadReviewerAgent reviewer;
    @Inject SecurityExpertAgent expert;

    public WorkflowResult orchestrate(Vulnerability vuln) {
        String fix = engineer.generateSecureFix(...);
        String review = reviewer.reviewCode(fix);
        String validation = expert.validate(fix);
        return buildConsensus(fix, review, validation);
    }
}
```

**After** — SwarmAI YAML workflow (`src/main/resources/workflows/my-workflow.yaml`):
```yaml
swarm:
  name: "vulnerability-patcher"
  process: SELF_IMPROVING

  agents:
    security-engineer:
      role: "Senior Security Engineer"
      goal: "Generate secure code fixes for vulnerabilities"
      backstory: "Expert in OWASP Top 10 and secure coding practices..."
      model: "gpt-4o"
      temperature: 0.3
      maxTurns: 5
      tools: [web_search, code_execution]

    reviewer:
      role: "Security Tech Lead"
      goal: "Review fixes for quality and security completeness"
      model: "gpt-4o"
      temperature: 0.4

    manager:
      role: "QA Director"
      goal: "Evaluate quality and identify capability gaps"

  managerAgent: manager

  tasks:
    analyze:
      description: "Analyze vulnerability {vulnerability_id}: {description}"
      agent: security-engineer

    fix:
      description: "Generate a secure fix based on the analysis"
      agent: security-engineer
      dependsOn: [analyze]

    review:
      description: "Review the fix for quality and security"
      agent: reviewer
      dependsOn: [fix]

  config:
    maxIterations: 3
    qualityCriteria: "Fix must be APPROVED and SECURE"
```

**Delete** the old agent classes, orchestrator, and LangChain config. They're replaced by the YAML.

### Step 5: Wire up the workflow

```java
@Configuration
public class SwarmConfig {

    @Bean
    @ConditionalOnBean(ChatClient.class)
    public SwarmCompiler swarmCompiler(ChatClient chatClient,
                                       ApplicationEventPublisher publisher) {
        return SwarmCompiler.builder()
                .chatClient(chatClient)
                .eventPublisher(publisher)
                .build();
    }

    @Bean
    @ConditionalOnBean(SwarmCompiler.class)
    public SwarmLoader swarmLoader(SwarmCompiler compiler) {
        return new SwarmLoader(new YamlSwarmParser(), compiler);
    }
}

@Service
@ConditionalOnBean(SwarmLoader.class)
public class MyWorkflowService {

    private final SwarmLoader loader;

    public MyWorkflowService(SwarmLoader loader) {
        this.loader = loader;
    }

    public SwarmOutput execute(Map<String, Object> inputs) {
        CompiledWorkflow workflow = loader.loadWorkflow("workflows/my-workflow.yaml");
        return workflow.kickoff(inputs);
    }
}
```

### Step 6: Configure application.yml

```yaml
spring:
  application:
    name: my-app
  main:
    allow-bean-definition-overriding: true
  ai:
    openai:
      api-key: ${OPENAI_API_KEY:}

swarmai:
  self-improving:
    enabled: true
    reserve-percent: 0.10
    github-token: ${GITHUB_TOKEN:}
    telemetry-enabled: true
  budget:
    enabled: true
    default-max-tokens: 500000
  memory:
    enabled: true
    provider: in-memory
  observability:
    enabled: true
```

### Step 7: Handle auto-configuration conflicts

If you get bean conflicts from Spring AI vector store or model auto-configs, exclude them:

```java
@SpringBootApplication(exclude = {
    DataSourceAutoConfiguration.class,
    HibernateJpaAutoConfiguration.class,
    FlywayAutoConfiguration.class
})
```

In tests, also exclude unused AI provider auto-configs:
```java
@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=" +
        "org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration," +
        "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration"
})
```

---

## Migration from Spring Boot + Custom Agents

If you already use Spring Boot but have custom agent logic:

### Step 1: Add SwarmAI dependencies

```xml
<dependency>
    <groupId>ai.intelliswarm</groupId>
    <artifactId>swarmai-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>ai.intelliswarm</groupId>
    <artifactId>swarmai-dsl</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>ai.intelliswarm</groupId>
    <artifactId>swarmai-self-improving</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Step 2: Create YAML workflow

Move your agent definitions and orchestration logic into a YAML file. See Step 4 above.

### Step 3: Enable self-improvement

Add to `application.yml`:
```yaml
swarmai:
  self-improving:
    enabled: true
```

That's it. Your existing Spring beans, REST controllers, and services stay unchanged.

---

## Migration from Spring AI (standalone)

Spring AI provides the LLM integration layer. SwarmAI adds orchestration on top.

### What stays the same
- `ChatClient` / `ChatModel` beans
- `@Tool` annotations
- `VectorStore` / `ChatMemory` configuration
- Spring Boot auto-configuration

### What SwarmAI adds
- Multi-agent orchestration (7 process types)
- YAML-defined workflows
- Self-improving loop (10% token investment)
- Budget tracking and governance
- Skill generation and promotion

```yaml
# Your agents are now defined declaratively
swarm:
  process: SELF_IMPROVING  # or SEQUENTIAL, PARALLEL, HIERARCHICAL, etc.
  agents:
    worker:
      role: "Your Agent Role"
      tools: [your_existing_tools]
  tasks:
    main:
      description: "What to do with {input}"
      agent: worker
```

---

## Migration from LangGraph4J

LangGraph4J gives you a graph execution engine. SwarmAI gives you 7 orchestration strategies + self-improvement.

| LangGraph4J | SwarmAI |
|---|---|
| `StateGraph<S>` | `SwarmGraph` or YAML DSL |
| `addNode(id, action)` | Agent + Task in YAML |
| `addEdge(from, to)` | `dependsOn` in tasks |
| `addConditionalEdge(from, router)` | Conditional tasks or COMPOSITE process |
| `CompiledGraph.invoke(state)` | `CompiledWorkflow.kickoff(inputs)` |
| `MemorySaver` | `Memory` interface (In-Memory, JDBC, Redis) |

### Key differences
- LangGraph4J: you design the graph manually
- SwarmAI: you pick a process type (SEQUENTIAL, PARALLEL, SELF_IMPROVING, etc.) and the framework handles orchestration
- SwarmAI adds: budget tracking, governance gates, skill generation, self-improvement

---

## Self-Improvement Setup Checklist

After migration, verify the self-improvement pipeline is wired correctly:

### Connected Environment (has internet)

```yaml
swarmai:
  self-improving:
    enabled: true
    github-token: ${GITHUB_TOKEN}  # enables auto-PR creation
    telemetry-enabled: true         # anonymized telemetry
```

Verify with test:
```java
@Autowired ImprovementPhase phase;
@Autowired ImprovementReportingService reporting;
@Autowired TelemetryReporter telemetry;

@Test
void selfImprovementWired() {
    assertNotNull(phase);
    assertNotNull(reporting);
    assertEquals(0.10, config.getReservePercent());
}
```

### Firewalled Environment (no internet)

```yaml
swarmai:
  self-improving:
    enabled: true
    telemetry-enabled: false  # no outbound calls
    # no github-token set
```

Improvements accumulate locally. The framework nudges ops to export:

- `/actuator/health` shows pending improvement count
- Startup banner reminds to export
- Periodic log messages with export instructions
- Export via: `POST /actuator/self-improving/export`

Submit the export file via:
1. GitHub issue at `github.com/intelliswarm-ai/swarm-ai/issues/new`
2. Email to `contributions@intelliswarm.ai`
3. Web form at `intelliswarm.ai/contribute`

### Verify Self-Improvement Health

```
GET /actuator/health

{
  "components": {
    "selfImprovement": {
      "status": "UP",
      "details": {
        "pendingImprovements": 0,
        "reportingStatus": "ONLINE",
        "communityROI": "0.0x"
      }
    }
  }
}
```

---

## Common Issues

### Bean definition override errors
```
Cannot register bean definition for bean 'vectorStore'
```
Fix: `spring.main.allow-bean-definition-overriding=true`

### Missing datasource
```
Failed to determine a suitable driver class
```
Fix: Exclude `DataSourceAutoConfiguration` and `HibernateJpaAutoConfiguration`

### Missing API key in tests
```
simpleApiKey cannot be null
```
Fix: Set dummy keys in test properties or exclude the auto-config:
```java
@SpringBootTest(properties = {
    "spring.ai.openai.api-key=test-key"
})
```

### HttpClient version conflict
```
NoClassDefFoundError: TlsSocketStrategy
```
Fix: Let Spring Boot manage the httpclient5 version (remove explicit `<version>`)

---

## What You Get After Migration

- **7 orchestration strategies** instead of custom orchestrator code
- **YAML-defined workflows** — change agent behavior without recompiling
- **10% self-improvement** — framework gets better on every run
- **Budget tracking** — know exactly what each workflow costs
- **Governance gates** — approval workflows for sensitive operations
- **Observability** — correlation IDs, tracing, health indicators
- **Community intelligence** — benefit from collective improvements on upgrade
