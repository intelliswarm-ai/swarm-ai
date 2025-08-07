#!/bin/bash

# SwarmAI Stock Analysis Example - Quick Run Script
# Automated setup and execution using Docker

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
COMPOSE_FILE="docker-compose.yml"
SERVICE_NAME="stock-analysis"
DEFAULT_TICKER="AAPL"

print_header() {
    echo -e "${BLUE}"
    echo "📊 SwarmAI Stock Analysis - Investment Research"
    echo "==============================================="
    echo -e "${NC}"
}

print_status() {
    echo -e "${GREEN}✅ $1${NC}"
}

print_error() {
    echo -e "${RED}❌ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

check_docker() {
    if ! command -v docker &> /dev/null; then
        print_error "Docker is not installed. Please install Docker and try again."
        exit 1
    fi
    
    if ! docker info &> /dev/null; then
        print_error "Docker daemon is not running. Please start Docker and try again."
        exit 1
    fi
    
    print_status "Docker is running"
}

cleanup() {
    echo -e "\n${YELLOW}🧹 Cleaning up...${NC}"
    docker compose -f $COMPOSE_FILE down --remove-orphans 2>/dev/null || true
}

main() {
    print_header
    
    # Set stock ticker
    STOCK_TICKER=${1:-$DEFAULT_TICKER}
    echo -e "${BLUE}📈 Analyzing Stock: ${STOCK_TICKER}${NC}\n"
    
    # Check prerequisites
    check_docker
    
    # Setup cleanup on exit
    trap cleanup EXIT
    
    # Set environment variable
    export STOCK_TICKER=$STOCK_TICKER
    
    echo -e "${BLUE}🔨 Building and starting services...${NC}"
    docker compose -f $COMPOSE_FILE up --build
    
    print_status "Stock analysis workflow completed!"
    echo -e "\n${GREEN}📄 Check the generated report for investment insights!${NC}"
}

# Handle script arguments
if [[ "$1" == "--help" ]] || [[ "$1" == "-h" ]]; then
    print_header
    echo "Usage: $0 [STOCK_TICKER]"
    echo ""
    echo "Examples:"
    echo "  $0           # Analyze AAPL (default)"
    echo "  $0 TSLA      # Analyze Tesla"
    echo "  $0 GOOGL     # Analyze Google"
    echo "  $0 MSFT      # Analyze Microsoft"
    exit 0
fi

# Run main function
main "$@"