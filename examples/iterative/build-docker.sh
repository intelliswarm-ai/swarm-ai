#!/bin/bash

# SwarmAI Iterative Investment Memo - Docker Image Builder
# Builds the Docker image from the project root context

set -e

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${BLUE}Building SwarmAI Iterative Investment Memo Docker image...${NC}"

# Check Docker
if ! command -v docker &> /dev/null; then
    echo -e "${RED}Docker is not installed.${NC}"
    exit 1
fi

# Navigate to project root
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"

echo -e "${BLUE}Project root: $PROJECT_ROOT${NC}"

# Check if JAR exists
if ! ls target/swarmai-framework-*.jar 1> /dev/null 2>&1; then
    echo -e "${BLUE}Building JAR with Maven...${NC}"
    ./mvnw clean package -DskipTests -q
fi

# Build Docker image using the example Dockerfile
echo -e "${BLUE}Building Docker image: swarmai-iterative:latest${NC}"
docker build -f examples/iterative/Dockerfile -t swarmai-iterative:latest .

echo -e "${GREEN}Docker image built successfully: swarmai-iterative:latest${NC}"
echo -e "${BLUE}Run with: cd examples/iterative && docker compose up --build${NC}"
