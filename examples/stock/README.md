# 📊 SwarmAI Stock Analysis Example

A comprehensive stock analysis workflow demonstrating the power of the SwarmAI framework with specialized financial agents.

## 🎯 Overview

This example showcases a multi-agent stock analysis system that provides investment recommendations through collaborative analysis by three specialized financial agents:

- **Financial Analyst** - Analyzes financial health, metrics, and performance
- **Research Analyst** - Gathers market news, sentiment, and industry insights  
- **Investment Advisor** - Synthesizes analysis into actionable investment recommendations

## 🏗️ Architecture

```
Stock Analysis Workflow (Sequential Process)
├── Financial Analyst Agent
│   ├── Financial health analysis
│   ├── Key metrics (P/E, EPS, revenue trends)
│   └── Industry peer comparison
├── Research Analyst Agent  
│   ├── Market news and sentiment
│   ├── Analyst opinions
│   └── Upcoming events (earnings)
├── SEC Filings Analysis
│   ├── 10-K and 10-Q analysis
│   ├── Management discussion
│   └── Risk assessment
└── Investment Recommendation
    ├── Comprehensive synthesis
    ├── Investment stance
    └── Supporting evidence
```

## 🛠️ Tools Available

- **Calculator Tool** - Mathematical calculations for financial metrics
- **Web Search Tool** - Market news and data gathering (mock implementation)
- **SEC Filings Tool** - Regulatory filing analysis (mock implementation)

## 🚀 Quick Start

### Prerequisites
- Java 21+
- [Ollama](https://ollama.ai) installed and running
- llama3.2:3b model pulled (`ollama pull llama3.2:3b`)

### Running Locally (with local Ollama)

```bash
# Navigate to the stock example directory
cd examples/stock

# Start Ollama (if not already running)
ollama serve
ollama pull llama3.2:3b

# Run analysis for Apple (default)
./run-local.sh

# Run analysis for Tesla
./run-local.sh TSLA

# Run analysis for Google
./run-local.sh GOOGL
```

### Running with Docker

```bash
# Navigate to the stock example directory
cd examples/stock

# Quick run with default stock (AAPL)
./run-stock.sh

# Run analysis for specific stock
./run-stock.sh MSFT

# Alternative: using docker-compose directly
STOCK_TICKER=AMZN docker-compose up --build
```

## 📈 Example Output

The workflow generates a comprehensive investment report including:

- **Executive Summary** - Key findings and recommendation
- **Financial Analysis** - Detailed financial health assessment
- **Market Research** - News sentiment and analyst opinions
- **SEC Filing Insights** - Regulatory compliance and risk factors
- **Investment Recommendation** - Clear buy/hold/sell guidance with rationale

Output is saved to `stock_analysis_report.md` in markdown format.

## ⚙️ Configuration

### Environment Variables
- `STOCK_TICKER` - Stock symbol to analyze (default: AAPL)
- `SPRING_AI_OLLAMA_BASE_URL` - Ollama endpoint (default: http://localhost:11434)
- `SPRING_PROFILES_ACTIVE` - Profile to use (default: local)

### Agent Configuration
- **Financial Analyst**: Temperature 0.1 (conservative analysis)
- **Research Analyst**: Temperature 0.3 (balanced research)
- **Investment Advisor**: Temperature 0.2 (measured recommendations)

## 📁 Directory Structure

```
examples/stock/
├── README.md              # This documentation
├── run-local.sh          # Local execution with Ollama
├── run-stock.sh          # Docker execution script
├── build-docker.sh       # Docker build script (alternative)
├── docker-compose.yml    # Docker services configuration  
├── Dockerfile           # Application container
└── config/              # Configuration files
```

## 🔧 Development

### Adding New Tools
1. Create a new tool class implementing `BaseTool` interface
2. Add to `src/main/java/ai/intelliswarm/swarmai/examples/stock/tools/`
3. Register as a Spring component with `@Component`
4. Inject into `StockAnalysisWorkflow`

### Customizing Agents
Edit `StockAnalysisWorkflow.java` to:
- Modify agent roles and backstories
- Adjust temperature settings
- Add or remove tools
- Change task descriptions

### Real API Integration
Replace mock implementations in tools with:
- Financial data APIs (Alpha Vantage, Yahoo Finance)
- SEC EDGAR database integration
- News APIs (NewsAPI, Google News)
- Market data providers (IEX Cloud, Quandl)

## 🎭 Example Usage Scenarios

```bash
# Technology stock analysis (local)
./run-local.sh NVDA

# Financial sector analysis (Docker)
./run-stock.sh JPM

# Growth stock evaluation (local)
./run-local.sh AMZN

# Dividend stock assessment (Docker)
./run-stock.sh KO
```

## 🔍 Troubleshooting

### Common Issues

1. **Ollama not running**
   ```bash
   ollama serve
   ollama pull llama3.2:3b
   ```

2. **Build failures**
   ```bash
   # From project root
   ./mvnw clean package -DskipTests
   ```

3. **Docker issues**
   ```bash
   docker-compose down
   docker system prune
   ./build-docker.sh
   ```

### Performance Tips
- Use local Ollama for faster response times
- Increase timeout values for complex analysis
- Cache results for repeated analysis

## 📚 Learn More

- [SwarmAI Documentation](../../README.md)
- [Agent Development Guide](../../docs/agents.md)
- [Tool Development Guide](../../docs/tools.md)
- [CrewAI Migration Guide](../../docs/migration.md)