#!/bin/bash

# SwarmAI Iterative Investment Memo - Docker Entrypoint Script
# Handles initialization and startup of the iterative refinement workflow

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

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

# Wait for Ollama to be ready
wait_for_ollama() {
    local max_attempts=30
    local attempt=1
    local ollama_url="${SPRING_AI_OLLAMA_BASE_URL:-http://ollama:11434}"

    print_info "Waiting for Ollama at $ollama_url..."

    while [ $attempt -le $max_attempts ]; do
        if curl -sf "$ollama_url/api/tags" > /dev/null 2>&1; then
            print_success "Ollama is ready!"
            return 0
        fi

        print_info "Attempt $attempt/$max_attempts - waiting for Ollama..."
        sleep 10
        attempt=$((attempt + 1))
    done

    print_error "Ollama is not ready after $max_attempts attempts"
    return 1
}

# Check if the required model is available
check_model() {
    local model="${SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL:-llama3.2:3b}"
    local ollama_url="${SPRING_AI_OLLAMA_BASE_URL:-http://ollama:11434}"

    print_info "Checking if model '$model' is available..."

    if curl -sf "$ollama_url/api/tags" | grep -q "\"name\":\"$model\""; then
        print_success "Model '$model' is available"
        return 0
    else
        print_warning "Model '$model' not found"
        return 1
    fi
}

# Test model responsiveness
test_model() {
    local model="${SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL:-llama3.2:3b}"
    local ollama_url="${SPRING_AI_OLLAMA_BASE_URL:-http://ollama:11434}"

    print_info "Testing model '$model'..."

    local test_response
    test_response=$(curl -sf -X POST "$ollama_url/api/generate" \
        -H "Content-Type: application/json" \
        -d "{\"model\":\"$model\",\"prompt\":\"Hello! Respond with 'Model ready'\",\"stream\":false}" | \
        grep -o '"response":"[^"]*"' | \
        sed 's/"response":"//;s/"//')

    if [ -n "$test_response" ]; then
        print_success "Model test successful: $test_response"
        return 0
    else
        print_error "Model test failed"
        return 1
    fi
}

# Main initialization
main() {
    echo
    echo -e "${CYAN}============================================================${NC}"
    echo -e "${CYAN}  SwarmAI - Iterative Investment Memo Workflow${NC}"
    echo -e "${CYAN}  Process: ITERATIVE (Execute -> Review -> Refine -> Repeat)${NC}"
    echo -e "${CYAN}============================================================${NC}"
    echo
    print_info "Ticker:         ${STOCK_TICKER:-NVDA}"
    print_info "Max Iterations: ${MAX_ITERATIONS:-3}"
    print_info "Model:          ${SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL:-llama3.2:3b}"
    echo

    # Wait for Ollama
    if ! wait_for_ollama; then
        print_error "Cannot connect to Ollama. Please ensure Ollama service is running."
        exit 1
    fi

    # Check model availability
    if ! check_model; then
        print_warning "Required model not available. The application may fail to start."
    fi

    # Test model
    if ! test_model; then
        print_warning "Model test failed. The application may not work correctly."
    fi

    echo
    print_info "Starting Iterative Investment Memo workflow..."
    print_info "Arguments: $*"
    echo

    # Build the arguments: workflow-type ticker max-iterations
    local workflow_cmd="$1"
    shift || true

    if [ -z "$workflow_cmd" ]; then
        workflow_cmd="iterative-memo"
    fi

    # Pass ticker and max iterations as arguments
    local ticker="${STOCK_TICKER:-NVDA}"
    local max_iter="${MAX_ITERATIONS:-3}"

    exec java $JAVA_OPTS -jar app.jar "$workflow_cmd" "$ticker" "$max_iter"
}

# Run main function
main "$@"
