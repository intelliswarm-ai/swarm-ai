#!/bin/bash

# SwarmAI Framework - Test Unified Model Configuration
# This script tests the unified model setup without Docker

echo "🚀 SwarmAI Framework - Testing Unified Model Setup"
echo "=================================================="

# Check if Ollama is installed locally
if ! command -v ollama &> /dev/null; then
    echo "❌ Ollama not found. Please install Ollama first:"
    echo "   curl -fsSL https://ollama.ai/install.sh | sh"
    exit 1
fi

# Check if Ollama is running
if ! curl -s http://localhost:11434/api/tags > /dev/null 2>&1; then
    echo "⚠️  Ollama is not running. Starting Ollama..."
    ollama serve &
    OLLAMA_PID=$!
    sleep 10
    
    if ! curl -s http://localhost:11434/api/tags > /dev/null 2>&1; then
        echo "❌ Failed to start Ollama"
        exit 1
    fi
fi

echo "✅ Ollama is running"

# Pull the unified model
echo "📦 Pulling unified model: llama3.2:3b"
ollama pull llama3.2:3b

if [ $? -eq 0 ]; then
    echo "✅ Model llama3.2:3b downloaded successfully"
else
    echo "❌ Failed to download model"
    exit 1
fi

# Test the model
echo "🧪 Testing model response"
TEST_RESPONSE=$(ollama run llama3.2:3b "Hello! Please respond with just 'Hello SwarmAI' and nothing else.")

if [[ "$TEST_RESPONSE" == *"Hello SwarmAI"* ]]; then
    echo "✅ Model test successful: $TEST_RESPONSE"
else
    echo "⚠️  Model test response: $TEST_RESPONSE"
fi

echo ""
echo "🎯 Ready to test SwarmAI Framework!"
echo "Run the competitive analysis example:"
echo "   mvn spring-boot:run -Dspring-boot.run.arguments=competitive-analysis"
echo ""
echo "Or build and run:"
echo "   mvn clean package -DskipTests"
echo "   java -jar target/swarmai-framework-1.0.0-SNAPSHOT.jar competitive-analysis"
echo ""