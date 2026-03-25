#!/bin/bash

# SwarmAI Iterative Investment Memo - Quick Run Script
# Automated setup and execution of the iterative refinement workflow

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Configuration
COMPOSE_FILE="docker-compose.yml"
SERVICE_NAME="iterative-app"
DEFAULT_TICKER="NVDA"
DEFAULT_MAX_ITERATIONS="3"

print_header() {
    echo -e "${CYAN}"
    echo "============================================================"
    echo "  SwarmAI - Iterative Investment Memo"
    echo "  Process: Execute -> Review -> Refine -> Repeat"
    echo "============================================================"
    echo -e "${NC}"
}

print_info() {
    echo -e "${BLUE}  $1${NC}"
}

print_success() {
    echo -e "${GREEN}  $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}  $1${NC}"
}

print_error() {
    echo -e "${RED}  $1${NC}"
}

check_prerequisites() {
    print_info "Checking prerequisites..."

    if ! command -v docker &> /dev/null; then
        print_error "Docker is not installed. Please install Docker first."
        exit 1
    fi

    if ! docker compose version &> /dev/null && ! command -v docker-compose &> /dev/null; then
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

    if docker compose version &> /dev/null; then
        DOCKER_COMPOSE_CMD="docker compose"
    else
        DOCKER_COMPOSE_CMD="docker-compose"
    fi

    export STOCK_TICKER="${STOCK_TICKER}"
    export MAX_ITERATIONS="${MAX_ITERATIONS}"

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
        if $DOCKER_COMPOSE_CMD ps $SERVICE_NAME | grep -q "healthy"; then
            print_success "Iterative application is healthy"
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

run_iterative_memo() {
    print_info "Running iterative investment memo workflow..."
    print_info "Ticker: ${STOCK_TICKER} | Max Iterations: ${MAX_ITERATIONS}"
    echo

    $DOCKER_COMPOSE_CMD exec -T $SERVICE_NAME java -jar app.jar iterative-memo "$STOCK_TICKER" "$MAX_ITERATIONS"

    if [ $? -eq 0 ]; then
        print_success "Iterative investment memo completed successfully"
    else
        print_error "Iterative investment memo failed"
        return 1
    fi
}

show_results() {
    print_info "Analysis Results:"

    if [ -d "./reports" ] && [ "$(ls -A ./reports 2>/dev/null)" ]; then
        print_success "Reports generated in ./reports/ directory:"
        ls -la ./reports/

        # Show a preview of the memo
        local memo_file
        memo_file=$(ls ./reports/investment_memo_*.md 2>/dev/null | head -1)
        if [ -n "$memo_file" ]; then
            echo
            print_info "Memo preview (first 30 lines):"
            head -30 "$memo_file"
            echo
            print_info "Full memo available at: $memo_file"
        fi
    else
        print_warning "No reports found in ./reports/ directory"
    fi

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
    print_header
    echo "Usage: $0 [TICKER] [MAX_ITERATIONS] [OPTIONS]"
    echo
    echo "Arguments:"
    echo "  TICKER          Stock ticker to analyze (default: NVDA)"
    echo "  MAX_ITERATIONS  Maximum review-refine cycles (default: 3)"
    echo
    echo "Options:"
    echo "  --help      Show this help message"
    echo "  --cleanup   Stop and remove all containers and volumes"
    echo "  --logs      Show application logs"
    echo "  --status    Show service status"
    echo
    echo "Examples:"
    echo "  $0                    # Analyze NVDA with 3 iterations"
    echo "  $0 TSLA               # Analyze Tesla with 3 iterations"
    echo "  $0 AAPL 5             # Analyze Apple with up to 5 iterations"
    echo "  $0 --cleanup          # Clean up after analysis"
    echo "  $0 --logs             # View application logs (watch the feedback loop!)"
    echo
    echo "What to watch for in the logs:"
    echo "  - ITERATION_STARTED    Each review-refine cycle beginning"
    echo "  - NEEDS_REFINEMENT     Reviewer feedback with specific issues"
    echo "  - APPROVED             Reviewer satisfied with the output"
    echo "  - Iteration count      How many cycles were needed"
    echo
}

show_logs() {
    print_info "Showing application logs (Ctrl+C to stop)..."
    print_info "Watch for ITERATION_STARTED, NEEDS_REFINEMENT, and APPROVED events"
    echo
    $DOCKER_COMPOSE_CMD logs --tail=150 -f $SERVICE_NAME
}

show_status() {
    print_info "Service Status:"
    $DOCKER_COMPOSE_CMD ps

    print_info "Health Check Status:"
    $DOCKER_COMPOSE_CMD exec -T ollama curl -sf http://localhost:11434/api/tags > /dev/null && print_success "Ollama: Healthy" || print_error "Ollama: Unhealthy"
    $DOCKER_COMPOSE_CMD exec -T $SERVICE_NAME curl -sf http://localhost:8080/actuator/health > /dev/null && print_success "Iterative App: Healthy" || print_error "Iterative App: Unhealthy"
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
            STOCK_TICKER="${STOCK_TICKER:-$DEFAULT_TICKER}"
            MAX_ITERATIONS="${MAX_ITERATIONS:-$DEFAULT_MAX_ITERATIONS}"
            check_prerequisites
            build_and_start_services
            if wait_for_services; then
                run_iterative_memo
                show_results
            else
                print_error "Services failed to start properly. Check logs with: $0 --logs"
                exit 1
            fi
            cleanup
            ;;
        *)
            # First arg is a ticker symbol
            STOCK_TICKER="$1"
            MAX_ITERATIONS="${2:-$DEFAULT_MAX_ITERATIONS}"
            check_prerequisites
            build_and_start_services
            if wait_for_services; then
                run_iterative_memo
                show_results
            else
                print_error "Services failed to start properly. Check logs with: $0 --logs"
                exit 1
            fi
            cleanup
            ;;
    esac
}

main "$@"
