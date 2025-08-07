#!/bin/bash

# SwarmAI Research Example - Local Ollama Execution Script
# Automated setup and execution with locally installed Ollama

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

print_header() {
    echo -e "${BLUE}"
    echo "🚀 SwarmAI Research - Competitive Analysis (Local Ollama)"
    echo "========================================================"
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
    
    # Check Docker
    if ! command -v docker &> /dev/null; then
        print_error "Docker is not installed. Please install Docker first."
        return 1
    fi
    
    # Check if research image exists
    if ! docker image inspect swarmai-research:latest &> /dev/null; then
        print_warning "Research Docker image not found. Building..."
        if ! ./build-docker.sh; then
            print_error "Failed to build Docker image"
            return 1
        fi
    fi
    
    print_success "Prerequisites check passed"
    return 0
}

setup_ollama() {
    print_info "Setting up local Ollama..."
    
    # Run the Ollama setup script
    if ! ./setup-local-ollama.sh setup; then
        print_error "Failed to setup Ollama"
        return 1
    fi
    
    return 0
}

start_research_app() {
    print_info "Starting research application..."
    
    # Start the application using local Ollama configuration
    docker compose -f docker-compose-local.yml up -d
    
    if [ $? -eq 0 ]; then
        print_success "Research application started"
        return 0
    else
        print_error "Failed to start research application"
        return 1
    fi
}

wait_for_health() {
    print_info "Waiting for application to become healthy..."
    
    local max_wait=120  # 2 minutes
    local wait_time=0
    local check_interval=10
    
    while [ $wait_time -lt $max_wait ]; do
        if curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1; then
            print_success "Application is healthy"
            return 0
        fi
        
        print_info "Waiting for application health... (${wait_time}s/${max_wait}s)"
        sleep $check_interval
        wait_time=$((wait_time + check_interval))
    done
    
    print_error "Application did not become healthy within $max_wait seconds"
    docker compose -f docker-compose-local.yml logs research-app --tail 20
    return 1
}

run_analysis() {
    print_info "Running competitive analysis workflow..."
    
    # Execute the analysis
    docker compose -f docker-compose-local.yml exec -T research-app java -jar app.jar competitive-analysis
    
    if [ $? -eq 0 ]; then
        print_success "Competitive analysis completed"
        return 0
    else
        print_error "Competitive analysis failed"
        return 1
    fi
}

show_results() {
    print_info "Analysis Results:"
    
    # Check if reports were generated
    if [ -d "./reports" ] && [ "$(ls -A ./reports 2>/dev/null)" ]; then
        print_success "Reports generated in ./reports/ directory:"
        ls -la ./reports/
        
        # Show preview of main report
        if [ -f "./reports/competitive_analysis_report.md" ]; then
            echo
            print_info "Report preview (first 15 lines):"
            head -15 ./reports/competitive_analysis_report.md
            echo
            print_info "📄 Full report: ./reports/competitive_analysis_report.md"
        fi
    else
        print_warning "No reports found in ./reports/ directory"
    fi
    
    # Show logs
    if [ -d "./logs" ] && [ "$(ls -A ./logs 2>/dev/null)" ]; then
        print_info "📋 Application logs: ./logs/"
    fi
}

cleanup() {
    if [ "$1" == "--cleanup" ]; then
        print_info "Cleaning up..."
        docker compose -f docker-compose-local.yml down
        ./setup-local-ollama.sh stop 2>/dev/null || true
        print_success "Cleanup completed"
    else
        print_info "Application is still running. Use '$0 --cleanup' to stop everything."
    fi
}

show_help() {
    echo "Usage: $0 [OPTIONS]"
    echo
    echo "Options:"
    echo "  --help      Show this help message"
    echo "  --cleanup   Stop application and Ollama"
    echo "  --logs      Show application logs"
    echo "  --status    Show service status"
    echo "  --setup     Setup Ollama only (don't run analysis)"
    echo
    echo "Examples:"
    echo "  $0                    # Run complete analysis workflow"
    echo "  $0 --cleanup          # Clean up after analysis"
    echo "  $0 --logs             # View application logs"
    echo "  $0 --setup            # Setup Ollama only"
    echo
}

show_logs() {
    print_info "Application logs:"
    docker compose -f docker-compose-local.yml logs research-app --tail=50 -f
}

show_status() {
    print_info "Service Status:"
    
    # Check Docker application
    if docker compose -f docker-compose-local.yml ps | grep -q "research-competitive-analysis.*Up"; then
        print_success "Research App: Running"
    else
        print_warning "Research App: Not running"
    fi
    
    # Check Ollama
    if curl -sf http://localhost:11434/api/tags > /dev/null 2>&1; then
        print_success "Ollama: Running"
        print_info "Available models:"
        curl -sf http://localhost:11434/api/tags | grep -o '"name":"[^"]*"' | sed 's/"name":"//;s/"//' | sed 's/^/  - /'
    else
        print_warning "Ollama: Not running"
    fi
    
    # Check application health
    if curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1; then
        print_success "Application Health: OK"
    else
        print_warning "Application Health: Not OK"
    fi
}

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
        "--setup")
            check_prerequisites && setup_ollama
            print_info "Ollama setup complete. Use '$0' to run the analysis."
            ;;
        "run"|"")
            if check_prerequisites; then
                if setup_ollama; then
                    if start_research_app; then
                        if wait_for_health; then
                            run_analysis
                            show_results
                        else
                            print_error "Application failed to start properly"
                            exit 1
                        fi
                    else
                        exit 1
                    fi
                else
                    exit 1
                fi
            else
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

main "$@"