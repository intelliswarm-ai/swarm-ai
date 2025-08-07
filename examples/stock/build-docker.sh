#!/bin/bash

# SwarmAI Stock Analysis Docker Build Script
echo "=================================================="
echo "SwarmAI Stock Analysis - Docker Build & Run"
echo "=================================================="

# Check if we're in the correct directory
if [ ! -f "docker-compose.yml" ]; then
    echo "❌ Please run this script from the examples/stock directory"
    exit 1
fi

# Check if Docker is running
if ! docker info >/dev/null 2>&1; then
    echo "❌ Docker is not running. Please start Docker and try again."
    exit 1
fi

# Set default stock ticker
STOCK_TICKER=${1:-AAPL}

echo "📊 Building Stock Analysis Example for: $STOCK_TICKER"
echo "🔧 Building Docker containers..."

# Build and start services
docker compose build

if [ $? -ne 0 ]; then
    echo "❌ Docker build failed"
    exit 1
fi

echo "🚀 Starting services..."

# Set stock ticker environment variable and start
export STOCK_TICKER=$STOCK_TICKER
docker compose up

echo "✅ Stock Analysis Complete!"