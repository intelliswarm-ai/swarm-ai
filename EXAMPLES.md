# SwarmAI Framework Examples

This document provides comprehensive examples and tutorials for using the SwarmAI Framework, including Docker deployment with Ollama for complete local AI inference.

## 🌟 Featured Example: Competitive Analysis Workflow

The **Competitive Analysis Workflow** demonstrates the full power of the SwarmAI Framework through a real-world multi-agent research scenario. This example showcases:

- **Multi-Agent Collaboration**: 5 specialized AI agents working together
- **Hierarchical Process Management**: Project manager coordinating agent activities  
- **Tool Integration**: Web search, data analysis, and report generation tools
- **Docker Deployment**: Complete containerized setup with Ollama
- **Professional Output**: Executive-ready competitive analysis reports

### 🎯 Workflow Overview

```
Project Manager Agent
├── Market Research Agent → Gathers competitive intelligence
├── Data Analyst Agent → Processes and analyzes data  
├── Strategy Consultant Agent → Develops recommendations
└── Report Writer Agent → Creates professional reports
```

### 🏗️ Architecture Features Demonstrated

#### Multi-Agent Coordination
- **Project Manager**: Orchestrates the entire workflow
- **Specialist Agents**: Each with unique roles, goals, and capabilities
- **Task Dependencies**: Sequential execution with context passing
- **Event-Driven Monitoring**: Real-time workflow tracking

#### Professional Tools Integration  
- **WebSearchTool**: Market intelligence gathering
- **DataAnalysisTool**: Competitive analysis and metrics
- **ReportGeneratorTool**: Executive report generation

#### Enterprise-Grade Output
- Professional markdown reports
- Executive summaries with strategic recommendations
- Competitive matrices and market analysis
- Implementation roadmaps and risk assessments

## 🚀 Quick Start with Docker & Ollama

### Prerequisites
- Docker and Docker Compose
- At least 8GB RAM (16GB recommended)
- 20GB disk space for models

### 1. Clone and Setup
```bash
git clone <repository>
cd swarm-ai
```

### 2. Start the Environment
```bash
# Start Ollama and SwarmAI services
docker-compose up --build

# In another terminal, setup Ollama models
./scripts/setup-ollama.sh
```

### 3. Run the Competitive Analysis Example
```bash
# Execute the workflow
docker-compose exec swarmai-app java -jar app.jar competitive-analysis
```

### 4. Monitor Progress
```bash
# View logs
docker-compose logs -f swarmai-app

# Check health status
curl http://localhost:8080/actuator/health

# View Ollama models (optional web UI)
open http://localhost:3000  # If using --profile webui
```

## 📊 Example Output

The competitive analysis workflow generates:

```
🎉 COMPETITIVE ANALYSIS WORKFLOW COMPLETED
================================================================================
📈 Execution Statistics:
  • Total Execution Time: 847 seconds
  • Success Rate: 100.0%
  • Tasks Completed: 4/4
  • Swarm ID: hierarchical-a8f9b2c1-4d5e-6f7g-8h9i-0j1k2l3m4n5o

📋 EXECUTIVE SUMMARY:
# Competitive Analysis Report: AI/ML Platform Services

**Analysis Date:** January 07, 2025
**Report Type:** Competitive Intelligence  
**Scope:** Market Analysis and Strategic Positioning

## Key Insights
Based on comprehensive multi-agent analysis, the AI/ML platform market shows...
[Full detailed analysis with strategic recommendations]

📊 Task Breakdown:
  ✅ Market Research & Data Collection: Success
  ✅ Data Analysis & Pattern Recognition: Success  
  ✅ Strategic Analysis & Recommendations: Success
  ✅ Executive Report Generation: Success

📄 Full report has been generated and saved to 'competitive_analysis_report.md'
```

## 🛠️ Configuration Options

### Ollama Model Configuration
```yaml
# application-docker.yml
spring.ai.ollama:
  base-url: http://ollama:11434
  chat.options:
    model: llama3.2:latest
    temperature: 0.7
    num-predict: 4000
```

### SwarmAI Workflow Configuration
```yaml
swarmai:
  default:
    max-rpm: 10                    # API rate limiting
    max-execution-time: 300000     # 5 minutes per task
    verbose: true                  # Detailed logging
    
examples:
  competitive-analysis:
    enabled: true
    max-execution-time: 600000     # 10 minutes for full workflow
    output-directory: /app/reports
```

## 🔧 Advanced Usage

### Running Without Docker
```bash
# Start Ollama locally
ollama serve

# Download required models
ollama pull llama3.2
ollama pull mistral:7b
ollama pull codellama:7b

# Configure application
export SPRING_AI_OLLAMA_BASE_URL=http://localhost:11434

# Run the application
mvn spring-boot:run -Dspring-boot.run.arguments=competitive-analysis
```

### Custom Model Configuration
```java
// Configure different models for different agents
Agent researcher = Agent.builder()
    .role("Market Research Analyst")
    .chatClient(chatClientBuilder
        .defaultOptions(OllamaOptions.create()
            .withModel("llama3.2:latest")
            .withTemperature(0.4f))
        .build())
    .build();

Agent analyst = Agent.builder()
    .role("Data Analyst") 
    .chatClient(chatClientBuilder
        .defaultOptions(OllamaOptions.create()
            .withModel("mistral:7b")
            .withTemperature(0.2f))
        .build())
    .build();
```

### Production Configuration
```bash
# Add additional services for production
docker-compose --profile postgres --profile redis --profile chromadb up

# This adds:
# - PostgreSQL for persistent memory storage
# - Redis for caching and session management  
# - ChromaDB for vector-based knowledge storage
```

## 🎛️ Customization Guide

### Creating Custom Tools
```java
@Component
public class CustomResearchTool implements BaseTool {
    @Override
    public String getFunctionName() { return "custom_search"; }
    
    @Override
    public Object execute(Map<String, Object> parameters) {
        // Your custom logic here
        return results;
    }
}
```

### Building Custom Workflows
```java
// Create your own multi-agent workflow
Swarm customSwarm = Swarm.builder()
    .agent(customAgent1)
    .agent(customAgent2) 
    .task(customTask1)
    .task(customTask2)
    .process(ProcessType.HIERARCHICAL)
    .eventPublisher(eventPublisher)
    .build();

SwarmOutput result = customSwarm.kickoff(inputs);
```

### Custom Report Templates
```java
// Modify ReportGeneratorTool for custom formats
private String generateCustomTemplate(String reportType, String content) {
    return String.format("""
        # Custom Report: %s
        
        %s
        
        Generated by: Custom SwarmAI Workflow
        """, reportType, content);
}
```

## 📈 Performance Optimization

### Memory Management
```bash
# Adjust JVM settings for large models
export JAVA_OPTS="-Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# Ollama memory configuration
export OLLAMA_KEEP_ALIVE=24h
```

### Scaling Considerations
- **CPU**: Each agent can utilize multiple CPU cores
- **Memory**: Large language models require significant RAM
- **Storage**: Model storage requires 3-7GB per model
- **Network**: API calls between services should be optimized

## 🔍 Monitoring and Debugging

### Health Checks
```bash
# Application health
curl http://localhost:8080/actuator/health

# Ollama status
curl http://localhost:11434/api/tags

# Model testing
./scripts/setup-ollama.sh test
```

### Logging Configuration
```yaml
logging:
  level:
    ai.intelliswarm.swarmai: DEBUG
    org.springframework.ai.ollama: DEBUG
  file:
    name: /app/logs/swarmai.log
```

### Event Monitoring
```java
@EventListener
public void handleSwarmEvent(SwarmEvent event) {
    logger.info("Workflow Event: {} - {} (Swarm: {})", 
        event.getType(), event.getMessage(), event.getSwarmId());
}
```

## 🌐 Integration Examples

### REST API Integration
```java
@RestController
public class SwarmController {
    
    @PostMapping("/api/swarms/competitive-analysis")
    public ResponseEntity<SwarmOutput> runAnalysis(@RequestBody Map<String, Object> inputs) {
        SwarmOutput result = competitiveAnalysisSwarm.kickoff(inputs);
        return ResponseEntity.ok(result);
    }
}
```

### Kubernetes Deployment
```yaml
# k8s/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: swarmai-app
spec:
  replicas: 2
  template:
    spec:
      containers:
      - name: swarmai
        image: swarmai-framework:latest
        env:
        - name: SPRING_AI_OLLAMA_BASE_URL
          value: "http://ollama-service:11434"
```

## 🎯 Next Steps

1. **Explore the Code**: Review the example implementation
2. **Customize Agents**: Modify roles, goals, and backstories
3. **Add Tools**: Integrate with your existing APIs and services
4. **Scale Up**: Deploy to production with Kubernetes
5. **Contribute**: Add your own examples and improvements

## 📚 Additional Resources

- **Framework Documentation**: [README.md](README.md)
- **API Reference**: http://localhost:8080/swagger-ui.html
- **License Information**: [LICENSE](LICENSE)
- **Attribution**: [ATTRIBUTION.md](ATTRIBUTION.md)

---

*This example demonstrates the power of collaborative AI intelligence through the SwarmAI Framework - a Java implementation inspired by CrewAI.*