# SwarmAI Research Example - Dockerized Competitive Analysis

This example demonstrates a fully dockerized competitive analysis research workflow using the SwarmAI framework with local Ollama LLM integration.

## 🎯 Overview

The research example showcases:
- **Multi-Agent Collaboration**: 5 specialized AI agents working together
- **Hierarchical Orchestration**: Project manager coordinating specialized teams
- **Unified Model Architecture**: Single `llama3.2:3b` model with temperature-based specialization
- **Containerized Deployment**: Complete Docker setup with minimal dependencies
- **Professional Output**: Executive-ready competitive analysis reports

## 🏗️ Architecture

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────────┐
│   Ollama LLM    │    │  SwarmAI Research│    │   Output Reports    │
│   llama3.2:3b   │◄──►│    Framework     │───►│   Markdown/HTML     │
│                 │    │                  │    │                     │
└─────────────────┘    └──────────────────┘    └─────────────────────┘
```

### Agent Specialization (Temperature-Based)
- **Project Manager** (0.3): Consistent coordination and decision-making
- **Market Researcher** (0.4): Factual research with slight creativity
- **Data Analyst** (0.2): Precise analytical work with minimal variance
- **Strategist** (0.5): Balanced creative strategic thinking
- **Report Writer** (0.6): Engaging content creation

## 🚀 Quick Start

### Prerequisites
- Docker and Docker Compose
- 4GB+ RAM available for containers
- Internet connection for model download (~1.7GB)

### 1. Build and Start Services
```bash
cd examples/research
docker-compose up --build
```

### 2. Run Competitive Analysis
Once services are healthy (check logs), run the analysis:
```bash
docker-compose exec research-app java -jar app.jar competitive-analysis
```

### 3. View Results
Reports will be generated in `./reports/` directory:
- `competitive_analysis_report.md` - Main executive report
- Logs available in `./logs/` directory

## 🔧 Configuration Options

### Environment Variables
```bash
# Model Configuration
SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL=llama3.2:3b
SPRING_AI_OLLAMA_CHAT_OPTIONS_TEMPERATURE=0.7

# Research Configuration
RESEARCH_MAX_EXECUTION_TIME=900000  # 15 minutes
SWARMAI_DEFAULT_MAX_RPM=15
SWARMAI_DEFAULT_VERBOSE=true

# Java Performance
JAVA_OPTS="-Xmx2g -XX:+UseG1GC"
```

### Custom Configuration
Edit `config/research-config.yml` to customize:
- Agent behavior and specialization
- Task timeouts and parameters
- Output formats and directories
- Tool configurations

## 📊 Monitoring

### Health Checks
```bash
# Check service health
curl http://localhost:8080/actuator/health

# Check Ollama status
curl http://localhost:11434/api/tags
```

### Logs
```bash
# Application logs
docker-compose logs research-app

# Ollama logs
docker-compose logs ollama

# Follow logs in real-time
docker-compose logs -f research-app
```

## 🔍 Advanced Usage

### Include Web UI for Model Management
```bash
docker-compose --profile webui up --build
# Access UI at http://localhost:3000
```

### Custom Analysis Parameters
```bash
# Run with custom industry focus
docker-compose exec research-app java -jar app.jar competitive-analysis \
  --industry="Fintech" \
  --scope="North American market" \
  --timeframe="2024-2026"
```

### Debugging Mode
```bash
# Enable debug logging
docker-compose exec research-app bash -c '
  LOGGING_LEVEL_AI_INTELLISWARM_SWARMAI=TRACE \
  java -jar app.jar competitive-analysis
'
```

## 📁 Project Structure

```
examples/research/
├── Dockerfile                 # Research-specific container
├── docker-compose.yml         # Complete environment setup
├── docker-entrypoint.sh      # Startup script with health checks
├── config/
│   └── research-config.yml   # Research workflow configuration
├── reports/                  # Generated analysis reports
├── logs/                     # Application logs
└── README.md                # This file
```

## 🛠️ Troubleshooting

### Common Issues

**Ollama fails to start:**
```bash
# Check if platform is correct for your system
docker-compose logs ollama

# Try without platform specification
# Edit docker-compose.yml and remove "platform: linux/amd64"
```

**Model download fails:**
```bash
# Manual model pull
docker-compose exec ollama ollama pull llama3.2:3b

# Check available space
docker system df
```

**Application startup timeout:**
```bash
# Increase startup time
# Edit healthcheck start_period in docker-compose.yml
start_period: 5m  # Increase from 2m to 5m
```

**Out of memory errors:**
```bash
# Increase container memory
# Edit JAVA_OPTS in docker-compose.yml
JAVA_OPTS="-Xmx4g -XX:+UseG1GC"
```

### Performance Optimization

**For faster execution:**
- Increase `max-rpm` values in configuration
- Reduce task timeouts for simpler analyses
- Use SSD storage for Docker volumes

**For better quality:**
- Increase task execution timeouts
- Enable verbose logging for debugging
- Customize agent temperatures for specific use cases

## 🤝 Contributing

To extend this research example:
1. Add new agent types in the workflow
2. Implement additional research tools
3. Create specialized analysis workflows
4. Add new output formats

## 📄 License

This example is part of the SwarmAI Framework, licensed under the MIT License.
See the main project LICENSE file for details.