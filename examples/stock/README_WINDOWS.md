# 📊 Running Stock Analysis on Windows

This guide explains how to run the SwarmAI Stock Analysis example on Windows systems.

## 🚀 Quick Start

### Option 1: Docker (Recommended)

#### Using Batch File (.bat)
```cmd
# Open Command Prompt or PowerShell
cd examples\stock

# Run with default stock (AAPL)
run-stock.bat

# Run with specific stock
run-stock.bat META
run-stock.bat TSLA
run-stock.bat GOOGL
```

#### Using PowerShell Script (.ps1)
```powershell
# Open PowerShell
cd examples\stock

# Run with default stock (AAPL)
.\run-stock.ps1

# Run with specific stock
.\run-stock.ps1 META
.\run-stock.ps1 -StockTicker NVDA

# Get help
.\run-stock.ps1 -Help
```

### Option 2: Local Execution with Ollama

```cmd
# Open Command Prompt
cd examples\stock

# Run with default stock (AAPL)
run-local.bat

# Run with specific stock
run-local.bat META
run-local.bat MSFT
```

## 📋 Prerequisites

### For Docker Method
1. **Docker Desktop for Windows**
   - Download: https://www.docker.com/products/docker-desktop
   - Ensure Docker Desktop is running
   - Enable WSL 2 backend (recommended)

2. **Windows Version**
   - Windows 10 version 2004+ (for WSL 2)
   - Windows 11 (any version)

### For Local Method
1. **Java 21+**
   - Download: https://adoptium.net/
   - Add to PATH environment variable

2. **Ollama**
   - Download: https://ollama.ai
   - Install and run: `ollama serve`
   - Pull model: `ollama pull llama3.2:3b`

3. **Maven** (optional if mvnw.cmd exists)
   - Download: https://maven.apache.org/
   - Add to PATH environment variable

## 🛠️ Troubleshooting

### PowerShell Script Not Running
If you get an execution policy error:
```powershell
# Allow script execution for current session
Set-ExecutionPolicy -ExecutionPolicy Bypass -Scope Process

# Or permanently for current user
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
```

### Docker Issues

#### Docker Desktop Not Running
```cmd
# Start Docker Desktop manually
# Or from PowerShell:
Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"
```

#### WSL 2 Not Installed
```powershell
# Install WSL 2
wsl --install

# Update WSL
wsl --update
```

### Ollama Issues

#### Ollama Not Running
```cmd
# Start Ollama service
ollama serve

# In another terminal, verify it's running
curl http://localhost:11434/api/tags
```

#### Model Not Found
```cmd
# Pull the required model
ollama pull llama3.2:3b

# List available models
ollama list
```

### Java Issues

#### Java Not Found
1. Download Java 21 from https://adoptium.net/
2. Install with "Add to PATH" option checked
3. Restart Command Prompt/PowerShell
4. Verify: `java -version`

### Build Issues

#### Maven Not Found
```cmd
# Use the Maven wrapper instead
mvnw.cmd clean package -DskipTests

# Or install Maven and add to PATH
```

## 📊 Example Commands

### Analyzing Different Sectors

#### Technology Stocks
```cmd
run-stock.bat AAPL   # Apple
run-stock.bat MSFT   # Microsoft
run-stock.bat GOOGL  # Google
run-stock.bat META   # Meta
run-stock.bat NVDA   # NVIDIA
```

#### Financial Stocks
```cmd
run-stock.bat JPM    # JP Morgan
run-stock.bat BAC    # Bank of America
run-stock.bat WFC    # Wells Fargo
```

#### E-commerce & Retail
```cmd
run-stock.bat AMZN   # Amazon
run-stock.bat WMT    # Walmart
run-stock.bat TGT    # Target
```

## 🎯 Advanced Usage

### Custom Configuration
Edit environment variables in the scripts:
- `DEFAULT_TICKER`: Default stock symbol
- `OLLAMA_URL`: Ollama API endpoint
- `COMPOSE_FILE`: Docker compose configuration

### Running with Custom LLM Provider
```cmd
# Set OpenAI API key
set OPENAI_API_KEY=your-api-key-here

# Run with OpenAI instead of Ollama
set SPRING_PROFILES_ACTIVE=openai
java -jar target\swarmai-framework-1.0.0-SNAPSHOT.jar stock-analysis META
```

### Batch Processing Multiple Stocks
Create a batch file `analyze-portfolio.bat`:
```batch
@echo off
for %%s in (AAPL MSFT GOOGL META NVDA) do (
    echo Analyzing %%s...
    call run-stock.bat %%s
    timeout /t 5 >nul
)
```

## 📝 Output

The analysis creates a `stock_analysis_report.md` file containing:
- Executive Summary
- Financial Health Analysis
- Market Sentiment
- SEC Filing Insights
- Investment Recommendation

## 🔧 Customization

### Modify Agent Behavior
Edit `StockAnalysisWorkflow.java` to adjust:
- Agent roles and backstories
- Temperature settings (0.0-1.0)
- Task descriptions
- Analysis depth

### Add Real Data Sources
Replace mock tools with real APIs:
- Alpha Vantage for market data
- SEC EDGAR for filings
- NewsAPI for news sentiment
- Yahoo Finance for financials

## 💡 Tips

1. **First Run**: The first execution takes longer as Docker pulls images
2. **Model Performance**: llama3.2:3b provides good balance of speed and quality
3. **API Keys**: Store sensitive keys in environment variables, not in code
4. **Report Location**: Reports are saved in the project root directory

## 🆘 Getting Help

If you encounter issues:
1. Check the logs for detailed error messages
2. Verify all prerequisites are installed
3. Ensure Docker Desktop is running (for Docker method)
4. Ensure Ollama is running (for local method)
5. Create an issue at: https://github.com/intelliswarm/swarmai/issues