# SwarmAI Framework

A Java multi-agent framework inspired by CrewAI, built on Spring AI. SwarmAI provides a powerful platform for creating and managing AI agent workflows in the Java ecosystem.

## Overview

SwarmAI is a complete migration of the CrewAI Python framework to Java, leveraging Spring AI for LLM integration and Spring Boot for enterprise-grade features. It enables you to create sophisticated multi-agent systems that can work together to solve complex problems.

## Key Features

- **Multi-Agent Orchestration**: Create and manage multiple AI agents working together
- **Flexible Task Management**: Define complex task dependencies and execution flows  
- **Multiple Process Types**: Sequential and hierarchical execution strategies
- **Spring AI Integration**: Support for OpenAI, Anthropic, Ollama, and other LLM providers
- **Memory & Knowledge Systems**: Built-in support for agent memory and knowledge bases
- **Event-Driven Architecture**: Comprehensive event system for monitoring and telemetry
- **Tool Integration**: Extensible tool system for agent capabilities
- **Enterprise Features**: Built on Spring Boot with observability, metrics, and configuration

## Architecture

### Core Components

- **Agent**: AI entities with specific roles, goals, and capabilities
- **Task**: Work units that agents execute with defined inputs and expected outputs
- **Swarm**: Orchestrator that manages agents and tasks execution
- **Process**: Execution strategies (Sequential, Hierarchical)
- **Memory**: Agent memory systems for learning and context retention
- **Knowledge**: Knowledge bases for agents to query information
- **Tools**: Extensible capabilities that agents can use

### Migration from CrewAI

| CrewAI (Python) | SwarmAI (Java) | Description |
|----------------|----------------|-------------|
| `Crew` | `Swarm` | Main orchestrator class |
| `CrewOutput` | `SwarmOutput` | Execution results |
| `CrewEvent` | `SwarmEvent` | Event system |
| Pydantic Models | Spring Boot Configuration | Type-safe configuration |
| Blinker Signals | Spring Application Events | Event handling |
| Python async/await | CompletableFuture/@Async | Asynchronous execution |

## Getting Started

### Prerequisites

- Java 21+
- Maven 3.6+
- LLM API key (OpenAI, Anthropic, or Ollama)

### Installation

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>ai.intelliswarm</groupId>
    <artifactId>swarmai-framework</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Configuration

Configure your LLM provider in `application.yml`:

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4o-mini
          temperature: 0.7
```

### Basic Usage

```java
@Component
public class MySwarmExample {
    
    @Autowired
    private ChatClient.Builder chatClientBuilder;
    
    @Autowired 
    private ApplicationEventPublisher eventPublisher;

    public void runSwarm() {
        // Create agents
        Agent researcher = Agent.builder()
            .role("Senior Research Analyst")
            .goal("Uncover cutting-edge developments in AI")
            .backstory("You work at a leading tech think tank")
            .chatClient(chatClientBuilder.build())
            .build();

        Agent writer = Agent.builder()
            .role("Tech Content Strategist") 
            .goal("Craft compelling content on tech advancements")
            .backstory("You are a renowned Content Strategist")
            .chatClient(chatClientBuilder.build())
            .build();

        // Create tasks
        Task researchTask = Task.builder()
            .description("Research latest AI advancements in 2024")
            .expectedOutput("A comprehensive 3-paragraph report")
            .agent(researcher)
            .build();

        Task writeTask = Task.builder()
            .description("Write an engaging blog post about AI advancements")
            .expectedOutput("A 4-paragraph blog post in markdown")
            .agent(writer)
            .dependsOn(researchTask)
            .build();

        // Create and execute swarm
        Swarm swarm = Swarm.builder()
            .agent(researcher)
            .agent(writer)
            .task(researchTask)
            .task(writeTask)
            .process(ProcessType.SEQUENTIAL)
            .eventPublisher(eventPublisher)
            .build();

        SwarmOutput result = swarm.kickoff(Map.of("topic", "AI in 2024"));
        System.out.println(result.getFinalOutput());
    }
}
```

## Process Types

### Sequential Process
Tasks execute in order, with each task potentially using outputs from previous tasks as context.

```java
Swarm swarm = Swarm.builder()
    .process(ProcessType.SEQUENTIAL)
    .agents(agents)
    .tasks(tasks)
    .build();
```

### Hierarchical Process  
A manager agent coordinates and delegates tasks to worker agents.

```java
Agent manager = Agent.builder()
    .role("Project Manager")
    .allowDelegation(true)
    .chatClient(chatClient)
    .build();

Swarm swarm = Swarm.builder()
    .process(ProcessType.HIERARCHICAL)
    .managerAgent(manager)
    .agents(workerAgents)
    .tasks(tasks)
    .build();
```

## Advanced Features

### Memory Integration
```java
Memory memory = new InMemoryMemory(); // or RedisMemory, PostgreSQLMemory

Agent agent = Agent.builder()
    .memory(memory)
    .build();
```

### Knowledge Bases
```java
Knowledge knowledge = new ChromaKnowledge(); // or PGVectorKnowledge

Agent agent = Agent.builder()
    .knowledge(knowledge)
    .build();
```

### Custom Tools
```java
public class WebSearchTool implements BaseTool {
    @Override
    public String getFunctionName() { return "web_search"; }
    
    @Override
    public Object execute(Map<String, Object> parameters) {
        // Implementation
    }
}

Agent agent = Agent.builder()
    .tool(new WebSearchTool())
    .build();
```

### Event Handling
```java
@EventListener
public void handleSwarmEvent(SwarmEvent event) {
    logger.info("Swarm event: {} - {}", event.getType(), event.getMessage());
}
```

## Configuration Options

```yaml
swarmai:
  default:
    max-rpm: 30
    max-execution-time: 300000
    verbose: false
    language: en
    
  memory:
    enabled: true
    provider: in-memory  # Options: in-memory, redis, postgresql
    
  knowledge:
    enabled: true
    provider: chroma  # Options: chroma, pgvector, in-memory
    
  telemetry:
    enabled: true
    export-interval: 30000
```

## Building the Project

```bash
mvn clean compile
mvn test
mvn package
```

## Contributing

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Credits and Attribution

This framework is a derivative work inspired by and adapted from [CrewAI](https://github.com/joaomdmoura/crewAI).

### Original Work
- **CrewAI**: Copyright (c) 2025 crewAI, Inc.
- **License**: MIT License
- **Repository**: https://github.com/joaomdmoura/crewAI

SwarmAI adapts CrewAI's innovative multi-agent concepts for the Java ecosystem with Spring AI integration, while maintaining the same open-source MIT License. See [ATTRIBUTION.md](ATTRIBUTION.md) for detailed attribution information.

### Acknowledgments
Special thanks to the CrewAI team for creating the original framework and fostering open-source collaboration in the multi-agent AI space."# swarm-ai" 
