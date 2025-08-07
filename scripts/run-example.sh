#!/bin/bash

# SwarmAI Framework - Example Runner Script
# 
# This script provides an easy way to run the competitive analysis example
# with proper environment setup and monitoring.

set -e  # Exit on any error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
NC='\033[0m' # No Color

# Configuration
EXAMPLE_NAME="competitive-analysis"
DOCKER_COMPOSE_FILE="docker-compose.yml"
MAX_WAIT_TIME=300  # 5 minutes
CHECK_INTERVAL=10   # 10 seconds

echo -e "${BLUE}🚀 SwarmAI Framework - Example Runner${NC}"
echo "========================================="

# Function to print colored output
print_status() {
    echo -e "${GREEN}✅ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

print_error() {
    echo -e "${RED}❌ $1${NC}"
}

print_info() {
    echo -e "${BLUE}ℹ️  $1${NC}"
}

print_highlight() {
    echo -e "${PURPLE}🎯 $1${NC}"
}

# Function to check if Docker is running
check_docker() {
    if ! docker ps > /dev/null 2>&1; then
        print_error "Docker is not running. Please start Docker first."
        return 1
    fi
    return 0
}

# Function to check if services are healthy
check_service_health() {
    local service=$1
    local max_attempts=$((MAX_WAIT_TIME / CHECK_INTERVAL))
    
    print_info "Checking health of $service..."
    
    for i in $(seq 1 $max_attempts); do
        if docker-compose ps "$service" | grep -q "healthy"; then
            print_status "$service is healthy"
            return 0
        elif docker-compose ps "$service" | grep -q "unhealthy"; then
            print_error "$service is unhealthy"
            return 1
        fi
        
        if [ $i -eq $max_attempts ]; then
            print_warning "$service health check timed out"
            return 1
        fi
        
        print_info "Waiting for $service to be healthy... (attempt $i/$max_attempts)"
        sleep $CHECK_INTERVAL
    done
}

# Function to setup and run the example
run_example() {
    local mode=${1:-docker}
    
    case $mode in
        "docker")
            run_docker_example
            ;;
        "local")
            run_local_example
            ;;
        *)
            print_error "Unknown mode: $mode. Use 'docker' or 'local'"
            exit 1
            ;;
    esac
}

# Function to run Docker-based example
run_docker_example() {
    print_highlight "Running SwarmAI Competitive Analysis Example with Docker + Ollama"
    echo
    
    # Check Docker
    if ! check_docker; then
        exit 1
    fi
    
    # Stop any existing containers
    print_info "Stopping any existing containers..."
    docker-compose down --remove-orphans
    
    # Start services
    print_info "Starting SwarmAI services..."
    print_info "This will download Ollama and SwarmAI images if not already present..."
    echo
    
    docker-compose up --build -d
    
    # Wait for services to be healthy
    print_info "Waiting for services to start up..."
    echo
    
    # Check Ollama health
    if ! check_service_health "ollama"; then
        print_error "Ollama service failed to start properly"
        print_info "Checking logs..."
        docker-compose logs ollama
        exit 1
    fi
    
    # Setup Ollama models
    print_info "Setting up Ollama models for SwarmAI..."
    if ! ./scripts/setup-ollama.sh; then
        print_error "Failed to setup Ollama models"
        exit 1
    fi
    
    # Check SwarmAI app health
    if ! check_service_health "swarmai-app"; then
        print_error "SwarmAI application failed to start properly"
        print_info "Checking logs..."
        docker-compose logs swarmai-app
        exit 1
    fi
    
    print_status "All services are running and healthy!"
    echo
    
    # Display service information
    print_info "Service Status:"
    docker-compose ps
    echo
    
    print_info "Service URLs:"
    print_info "  • SwarmAI Application: http://localhost:8080"
    print_info "  • Health Check: http://localhost:8080/actuator/health" 
    print_info "  • API Documentation: http://localhost:8080/swagger-ui.html"
    print_info "  • Ollama API: http://localhost:11434"
    if docker-compose --profile webui config > /dev/null 2>&1; then
        print_info "  • Ollama Web UI: http://localhost:3000"
    fi
    echo
    
    # Run the example
    print_highlight "Executing Competitive Analysis Workflow..."
    print_info "This will demonstrate multi-agent collaboration for market research"
    echo
    
    if docker-compose exec -T swarmai-app java -jar app.jar $EXAMPLE_NAME; then
        print_status "Competitive Analysis Workflow completed successfully!"
        echo
        print_info "Generated reports should be available in the ./reports/ directory"
        print_info "Check application logs for detailed execution information"
    else
        print_error "Competitive Analysis Workflow failed"
        print_info "Checking application logs..."
        docker-compose logs --tail=50 swarmai-app
        exit 1
    fi
}

# Function to run local example
run_local_example() {
    print_highlight "Running SwarmAI Competitive Analysis Example locally"
    echo
    
    # Check if Ollama is running locally
    if ! curl -s http://localhost:11434/api/tags > /dev/null; then
        print_error "Ollama is not running locally at http://localhost:11434"
        print_info "Please start Ollama first:"
        print_info "  ollama serve"
        exit 1
    fi
    
    # Setup models if needed
    print_info "Setting up Ollama models..."
    export OLLAMA_HOST="http://localhost:11434"
    ./scripts/setup-ollama.sh
    
    # Run the application locally
    print_info "Starting SwarmAI application locally..."
    mvn spring-boot:run -Dspring-boot.run.arguments=$EXAMPLE_NAME
}

# Function to show logs
show_logs() {
    local service=${1:-}
    
    if [ -n "$service" ]; then
        print_info "Showing logs for $service..."
        docker-compose logs -f "$service"
    else
        print_info "Showing logs for all services..."
        docker-compose logs -f
    fi
}

# Function to cleanup
cleanup() {
    print_info "Cleaning up SwarmAI services..."
    docker-compose down --remove-orphans
    print_status "Cleanup completed"
}

# Function to show help
show_help() {
    echo "SwarmAI Framework Example Runner"
    echo
    echo "Usage: $0 [command] [options]"
    echo
    echo "Commands:"
    echo "  run [docker|local]  - Run the competitive analysis example (default: docker)"
    echo "  logs [service]      - Show logs for all services or specific service"  
    echo "  cleanup             - Stop and remove all containers"
    echo "  status              - Show status of all services"
    echo "  help                - Show this help message"
    echo
    echo "Examples:"
    echo "  $0 run docker       - Run with Docker + Ollama (recommended)"
    echo "  $0 run local        - Run locally (requires local Ollama)"
    echo "  $0 logs swarmai-app - Show application logs"
    echo "  $0 cleanup          - Stop all services"
    echo
}

# Function to show status
show_status() {
    print_info "SwarmAI Services Status:"
    echo
    docker-compose ps
    echo
    
    print_info "Health Checks:"
    echo "SwarmAI App:"
    curl -s http://localhost:8080/actuator/health | jq '.' 2>/dev/null || echo "  Not accessible"
    echo
    echo "Ollama:"
    curl -s http://localhost:11434/api/tags | jq '.models | length' 2>/dev/null || echo "  Not accessible"
    echo
}

# Main execution
case "${1:-run}" in
    "run")
        run_example "${2:-docker}"
        ;;
    "logs")
        show_logs "$2"
        ;;
    "cleanup")
        cleanup
        ;;
    "status")
        show_status
        ;;
    "help"|"--help"|"-h")
        show_help
        ;;
    *)
        print_error "Unknown command: $1"
        show_help
        exit 1
        ;;
esac