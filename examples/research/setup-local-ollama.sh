#!/bin/bash

# SwarmAI Research Example - Local Ollama Setup
# This script sets up Ollama locally to work with the dockerized research application

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

print_header() {
    echo -e "${BLUE}"
    echo "🚀 SwarmAI Research - Local Ollama Setup"
    echo "========================================"
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

check_ollama_installation() {
    print_info "Checking Ollama installation..."
    
    if command -v ollama &> /dev/null; then
        print_success "Ollama is installed"
        return 0
    else
        print_warning "Ollama is not installed"
        return 1
    fi
}

install_ollama() {
    print_info "Installing Ollama..."
    
    # Check if we're on WSL/Linux
    if [[ "$OSTYPE" == "linux-gnu"* ]]; then
        curl -fsSL https://ollama.ai/install.sh | sh
    else
        print_error "Please install Ollama manually from https://ollama.ai/download"
        return 1
    fi
    
    if command -v ollama &> /dev/null; then
        print_success "Ollama installed successfully"
        return 0
    else
        print_error "Ollama installation failed"
        return 1
    fi
}

check_ollama_service() {
    print_info "Checking if Ollama service is running..."
    
    if curl -sf http://localhost:11434/api/tags > /dev/null 2>&1; then
        print_success "Ollama service is running"
        return 0
    else
        print_warning "Ollama service is not running"
        return 1
    fi
}

start_ollama_service() {
    print_info "Starting Ollama service..."
    
    # Start Ollama in the background
    nohup ollama serve > ollama.log 2>&1 &
    OLLAMA_PID=$!
    
    # Wait for it to start
    sleep 5
    
    # Check if it's running
    if check_ollama_service; then
        print_success "Ollama service started (PID: $OLLAMA_PID)"
        echo $OLLAMA_PID > .ollama_pid
        return 0
    else
        print_error "Failed to start Ollama service"
        return 1
    fi
}

pull_model() {
    local model="llama3.2:3b"
    print_info "Pulling model: $model"
    
    # Check if model already exists
    if ollama list | grep -q "$model"; then
        print_success "Model $model already exists"
        return 0
    fi
    
    # Pull the model
    ollama pull "$model"
    
    if [ $? -eq 0 ]; then
        print_success "Model $model pulled successfully"
        return 0
    else
        print_error "Failed to pull model $model"
        return 1
    fi
}

test_model() {
    local model="llama3.2:3b"
    print_info "Testing model: $model"
    
    local response
    response=$(ollama run "$model" "Hello! Please respond with 'Model Ready'" --timeout 30s 2>/dev/null || echo "")
    
    if [[ "$response" == *"Model Ready"* ]]; then
        print_success "Model test successful: $response"
        return 0
    else
        print_warning "Model test response: ${response:-No response}"
        return 0  # Don't fail the setup if test is inconclusive
    fi
}

show_usage_instructions() {
    echo
    print_info "🎯 Setup Complete! Next Steps:"
    echo
    print_info "1. Start the research application:"
    echo "   docker compose -f docker-compose-local.yml up"
    echo
    print_info "2. In another terminal, run the competitive analysis:"
    echo "   docker compose -f docker-compose-local.yml exec research-app java -jar app.jar competitive-analysis"
    echo
    print_info "3. Or use the automated script:"
    echo "   ./run-research-local.sh"
    echo
    print_info "4. Monitor Ollama logs:"
    echo "   tail -f ollama.log"
    echo
    print_info "5. Stop Ollama when done:"
    echo "   kill \$(cat .ollama_pid) || pkill ollama"
    echo
}

cleanup_on_exit() {
    if [ -f .ollama_pid ]; then
        local pid=$(cat .ollama_pid)
        if ps -p $pid > /dev/null 2>&1; then
            print_info "Ollama is still running (PID: $pid)"
            print_info "To stop: kill $pid"
        fi
    fi
}

main() {
    print_header
    
    # Check if Ollama is installed
    if ! check_ollama_installation; then
        print_info "Installing Ollama..."
        if ! install_ollama; then
            exit 1
        fi
    fi
    
    # Check if Ollama service is running
    if ! check_ollama_service; then
        if ! start_ollama_service; then
            exit 1
        fi
    fi
    
    # Pull the required model
    if ! pull_model; then
        print_error "Failed to pull model, but continuing..."
    fi
    
    # Test the model
    test_model
    
    # Show usage instructions
    show_usage_instructions
    
    # Set up cleanup
    trap cleanup_on_exit EXIT
}

# Handle command line arguments
case "${1:-setup}" in
    "setup")
        main
        ;;
    "start")
        if check_ollama_service; then
            print_success "Ollama is already running"
        else
            start_ollama_service
        fi
        ;;
    "stop")
        if [ -f .ollama_pid ]; then
            kill $(cat .ollama_pid) && rm .ollama_pid
            print_success "Ollama stopped"
        else
            pkill ollama && print_success "Ollama stopped"
        fi
        ;;
    "status")
        if check_ollama_service; then
            print_success "Ollama is running"
            ollama list
        else
            print_warning "Ollama is not running"
        fi
        ;;
    "help")
        echo "Usage: $0 [setup|start|stop|status|help]"
        echo
        echo "Commands:"
        echo "  setup  - Install and configure Ollama (default)"
        echo "  start  - Start Ollama service"
        echo "  stop   - Stop Ollama service"
        echo "  status - Check Ollama status and list models"
        echo "  help   - Show this help message"
        ;;
    *)
        print_error "Unknown command: $1"
        echo "Use '$0 help' for usage information"
        exit 1
        ;;
esac