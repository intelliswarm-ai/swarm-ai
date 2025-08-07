#!/bin/bash

# SwarmAI Stock Analysis - Local Execution Script
echo "=================================================="
echo "SwarmAI Stock Analysis - Local Execution"
echo "=================================================="

# Check if we're in the correct directory
if [ ! -f "../../mvnw" ]; then
    echo "❌ Please run this script from the examples/stock directory"
    exit 1
fi

# Set default stock ticker
STOCK_TICKER=${1:-AAPL}

echo "📊 Running Stock Analysis for: $STOCK_TICKER"
echo "🔧 Using local Ollama instance at http://localhost:11434"
echo ""

# Check if Ollama is running
if ! curl -f http://localhost:11434/api/version >/dev/null 2>&1; then
    echo "❌ Ollama is not running at http://localhost:11434"
    echo "   Please start Ollama first:"
    echo "   - Install: https://ollama.ai"
    echo "   - Run: ollama serve"
    echo "   - Pull model: ollama pull llama3.2:3b"
    exit 1
fi

echo "✅ Ollama is running!"

# Build the project from the root
echo "🔨 Building SwarmAI Framework..."
cd ../..
./mvnw clean package -DskipTests

if [ $? -ne 0 ]; then
    echo "❌ Build failed"
    exit 1
fi

echo "🚀 Starting Stock Analysis..."
echo ""

# Run the application
SPRING_PROFILES_ACTIVE=local SPRING_AI_OLLAMA_BASE_URL=http://localhost:11434 \
timeout 120s java -jar target/swarmai-framework-*.jar stock-analysis $STOCK_TICKER

echo ""
echo "✅ Stock Analysis Complete!"