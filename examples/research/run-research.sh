#!/bin/bash

# SwarmAI Research Example - Quick Run Script
# Automated setup and execution of the competitive analysis workflow

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
COMPOSE_FILE="docker-compose.yml"
SERVICE_NAME="research-app"
ANALYSIS_TYPE="competitive-analysis"

# Functions
print_header() {
    echo -e "${BLUE}"
    echo "🚀 SwarmAI Research Example - Competitive Analysis"
    echo "=================================================="
    echo -e "${NC}"
}

print_info() {
    echo -e "${BLUE}ℹ️  $1${NC}"
}

print_success() {
    echo -e "${GREEN}✅ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

print_error() {
    echo -e "${RED}❌ $1${NC}"
}

check_prerequisites() {
    print_info "Checking prerequisites..."
    
    if ! command -v docker &> /dev/null; then
        print_error "Docker is not installed. Please install Docker first."
        exit 1
    fi
    
    if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null; then
        print_error "Docker Compose is not installed. Please install Docker Compose first."
        exit 1
    fi
    
    # Check available disk space (need ~2GB for model)
    available_space=$(df -BG . | awk 'NR==2 {print $4}' | sed 's/G//')
    if [ "$available_space" -lt 3 ]; then
        print_warning "Low disk space detected. Need at least 3GB free for model download."
    fi
    
    print_success "Prerequisites check passed"
}

build_and_start_services() {
    print_info "Building and starting services..."
    
    # Use docker compose or docker-compose based on availability
    if docker compose version &> /dev/null; then
        DOCKER_COMPOSE_CMD="docker compose"
    else
        DOCKER_COMPOSE_CMD="docker-compose"
    fi
    
    # Build and start services
    $DOCKER_COMPOSE_CMD up --build -d
    
    if [ $? -eq 0 ]; then
        print_success "Services started successfully"
    else
        print_error "Failed to start services"
        exit 1
    fi
}

wait_for_services() {
    print_info "Waiting for services to become healthy..."
    
    local max_wait=300  # 5 minutes
    local wait_time=0
    local check_interval=15
    
    while [ $wait_time -lt $max_wait ]; do
        # Check if research-app is healthy
        if $DOCKER_COMPOSE_CMD ps research-app | grep -q "healthy"; then
            print_success "Research application is healthy"
            return 0
        fi
        
        print_info "Waiting for services... (${wait_time}s/${max_wait}s)"
        sleep $check_interval
        wait_time=$((wait_time + check_interval))
    done
    
    print_error "Services did not become healthy within $max_wait seconds"
    print_info "Checking service status..."
    $DOCKER_COMPOSE_CMD ps
    return 1
}

run_competitive_analysis() {
    print_info "Running competitive analysis workflow..."
    
    # Run the analysis
    $DOCKER_COMPOSE_CMD exec -T $SERVICE_NAME java -jar app.jar $ANALYSIS_TYPE
    
    if [ $? -eq 0 ]; then
        print_success "Competitive analysis completed successfully"
    else
        print_error "Competitive analysis failed"
        return 1
    fi
}

show_results() {
    print_info "Analysis Results:"
    
    # Check if reports were generated
    if [ -d "./reports" ] && [ "$(ls -A ./reports)" ]; then
        print_success "Reports generated in ./reports/ directory:"
        ls -la ./reports/
        
        # Show a preview of the main report if it exists
        if [ -f "./reports/competitive_analysis_report.md" ]; then
            echo
            print_info "Report preview (first 20 lines):"
            head -20 ./reports/competitive_analysis_report.md
            echo
            print_info "Full report available at: ./reports/competitive_analysis_report.md"
        fi
    else
        print_warning "No reports found in ./reports/ directory"
    fi
    
    # Show logs location
    print_info "Application logs available at: ./logs/"
}

cleanup() {
    if [ "$1" == "--cleanup" ]; then
        print_info "Cleaning up containers and volumes..."
        $DOCKER_COMPOSE_CMD down -v
        print_success "Cleanup completed"
    else
        print_info "Services are still running. Use '$0 --cleanup' to stop and remove them."
    fi
}

show_help() {
    echo "Usage: $0 [OPTIONS]"
    echo
    echo "Options:"
    echo "  --help      Show this help message"
    echo "  --cleanup   Stop and remove all containers and volumes"
    echo "  --logs      Show application logs"
    echo "  --status    Show service status"
    echo
    echo "Examples:"
    echo "  $0                    # Run complete analysis workflow"
    echo "  $0 --cleanup          # Clean up after analysis"
    echo "  $0 --logs             # View application logs"
    echo
}

show_logs() {
    print_info "Showing application logs..."
    $DOCKER_COMPOSE_CMD logs --tail=100 -f $SERVICE_NAME
}

show_status() {
    print_info "Service Status:"
    $DOCKER_COMPOSE_CMD ps
    
    print_info "Health Check Status:"
    $DOCKER_COMPOSE_CMD exec -T ollama curl -sf http://localhost:11434/api/tags > /dev/null && print_success "Ollama: Healthy" || print_error "Ollama: Unhealthy"
    $DOCKER_COMPOSE_CMD exec -T $SERVICE_NAME curl -sf http://localhost:8080/actuator/health > /dev/null && print_success "Research App: Healthy" || print_error "Research App: Unhealthy"
}

# Main execution
main() {
    print_header
    
    case "${1:-run}" in
        "--help"|"-h")
            show_help
            ;;
        "--cleanup")
            cleanup --cleanup
            ;;
        "--logs")
            show_logs
            ;;
        "--status")
            show_status
            ;;
        "run"|"")
            check_prerequisites
            build_and_start_services
            if wait_for_services; then
                run_competitive_analysis
                show_results
            else
                print_error "Services failed to start properly. Check logs with: $0 --logs"
                exit 1
            fi
            cleanup
            ;;
        *)
            print_error "Unknown option: $1"
            show_help
            exit 1
            ;;
    esac
}

# Run main function with all arguments
main "$@"