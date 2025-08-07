#!/bin/bash

# SwarmAI Research Example - Docker Entrypoint Script
# Handles initialization and startup of the competitive analysis workflow

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
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

# Function to wait for Ollama to be ready
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

# Function to check if required model is available
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

# Function to test model
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
    print_info "🚀 SwarmAI Research Example - Competitive Analysis"
    print_info "=================================================="
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
    print_info "Starting SwarmAI Research Example..."
    print_info "Arguments: $*"
    echo
    
    # Start the application
    exec java $JAVA_OPTS -jar app.jar "$@"
}

# Run main function
main "$@"