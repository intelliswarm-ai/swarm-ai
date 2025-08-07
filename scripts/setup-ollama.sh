#!/bin/bash

# SwarmAI Framework - Ollama Setup Script
# 
# This script sets up Ollama with the required models for SwarmAI examples
# and ensures proper configuration for the competitive analysis workflow.

set -e  # Exit on any error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
OLLAMA_HOST="${OLLAMA_HOST:-http://localhost:11434}"
REQUIRED_MODELS=("llama3.2:3b")
MAX_RETRIES=5
RETRY_DELAY=10

echo -e "${BLUE}🚀 SwarmAI Framework - Ollama Setup Script${NC}"
echo "=================================="

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

# Function to check if Ollama is running
check_ollama() {
    if curl -s "$OLLAMA_HOST/api/tags" > /dev/null 2>&1; then
        return 0
    else
        return 1
    fi
}

# Function to wait for Ollama to be ready
wait_for_ollama() {
    print_info "Waiting for Ollama to be ready at $OLLAMA_HOST..."
    
    for i in $(seq 1 $MAX_RETRIES); do
        if check_ollama; then
            print_status "Ollama is ready!"
            return 0
        fi
        
        if [ $i -eq $MAX_RETRIES ]; then
            print_error "Ollama is not responding after $MAX_RETRIES attempts"
            print_error "Please ensure Ollama is running and accessible at $OLLAMA_HOST"
            return 1
        fi
        
        print_info "Attempt $i/$MAX_RETRIES - Ollama not ready, waiting ${RETRY_DELAY}s..."
        sleep $RETRY_DELAY
    done
}

# Function to check if a model is already downloaded
model_exists() {
    local model=$1
    if curl -s "$OLLAMA_HOST/api/tags" | grep -q "\"name\":\"$model\""; then
        return 0
    else
        return 1
    fi
}

# Function to pull a model
pull_model() {
    local model=$1
    print_info "Downloading model: $model"
    
    # Use Ollama API to pull the model
    if curl -s -X POST "$OLLAMA_HOST/api/pull" \
        -H "Content-Type: application/json" \
        -d "{\"name\":\"$model\"}" > /dev/null; then
        print_status "Successfully downloaded: $model"
        return 0
    else
        print_error "Failed to download: $model"
        return 1
    fi
}

# Function to list available models
list_models() {
    print_info "Available models on Ollama:"
    curl -s "$OLLAMA_HOST/api/tags" | \
        grep -o '"name":"[^"]*"' | \
        sed 's/"name":"//;s/"//' | \
        sed 's/^/  - /'
}

# Function to run model test
test_model() {
    local model=$1
    print_info "Testing model: $model"
    
    local test_response
    test_response=$(curl -s -X POST "$OLLAMA_HOST/api/generate" \
        -H "Content-Type: application/json" \
        -d "{\"model\":\"$model\",\"prompt\":\"Hello, world! Respond with just 'Hello' and nothing else.\",\"stream\":false}" | \
        grep -o '"response":"[^"]*"' | \
        sed 's/"response":"//;s/"//')
    
    if [ -n "$test_response" ]; then
        print_status "Model $model is working correctly"
        print_info "Test response: $test_response"
        return 0
    else
        print_error "Model $model test failed"
        return 1
    fi
}

# Function to setup SwarmAI specific configuration
setup_swarmai_config() {
    print_info "Setting up SwarmAI specific configuration..."
    
    # Create models directory if it doesn't exist
    mkdir -p models
    
    # Create a model configuration file
    cat > models/swarmai-models.json << EOF
{
  "swarmai_models": {
    "unified_model": {
      "model": "llama3.2:3b",
      "temperature": 0.7,
      "max_tokens": 2048,
      "description": "Single unified model for all agents - faster and more efficient"
    },
    "agent_configurations": {
      "research_agent": {
        "temperature": 0.4,
        "description": "Research and data gathering with lower temperature for accuracy"
      },
      "analysis_agent": {
        "temperature": 0.2,
        "description": "Analytical tasks with very low temperature for precision"
      },
      "strategy_agent": {
        "temperature": 0.5,
        "description": "Strategic thinking with moderate temperature"
      },
      "writer_agent": {
        "temperature": 0.6,
        "description": "Content creation with higher temperature for creativity"
      },
      "manager_agent": {
        "temperature": 0.3,
        "description": "Project coordination with low temperature for consistency"
      }
    }
  }
}
EOF

    print_status "Created SwarmAI model configuration"
}

# Main execution
main() {
    echo
    print_info "Starting Ollama setup for SwarmAI Framework..."
    echo
    
    # Check if Ollama is running
    if ! wait_for_ollama; then
        exit 1
    fi
    
    echo
    print_info "Current Ollama status:"
    list_models
    echo
    
    # Download required models
    print_info "Checking and downloading required models..."
    for model in "${REQUIRED_MODELS[@]}"; do
        if model_exists "$model"; then
            print_status "Model already exists: $model"
        else
            print_info "Model not found, downloading: $model"
            if pull_model "$model"; then
                # Wait a bit for the model to be fully loaded
                sleep 5
            else
                print_error "Failed to download $model - continuing with other models"
                continue
            fi
        fi
        
        # Test the model
        if ! test_model "$model"; then
            print_warning "Model $model downloaded but test failed"
        fi
        
        echo
    done
    
    # Setup SwarmAI configuration
    setup_swarmai_config
    
    echo
    print_status "Ollama setup completed successfully!"
    print_info "Available models:"
    list_models
    
    echo
    print_info "To run the SwarmAI competitive analysis example:"
    print_info "  docker-compose up --build"
    print_info "  # Wait for services to start, then in another terminal:"
    print_info "  docker-compose exec swarmai-app java -jar app.jar competitive-analysis"
    echo
    print_info "Or run locally:"
    print_info "  mvn spring-boot:run -Dspring-boot.run.arguments=competitive-analysis"
    echo
    print_info "Using unified model: llama3.2:3b for all agents (faster download & execution)"
    echo
}

# Handle script arguments
case "${1:-setup}" in
    "setup")
        main
        ;;
    "test")
        if wait_for_ollama; then
            for model in "${REQUIRED_MODELS[@]}"; do
                test_model "$model"
            done
        fi
        ;;
    "list")
        if wait_for_ollama; then
            list_models
        fi
        ;;
    "pull")
        if [ -z "$2" ]; then
            print_error "Usage: $0 pull <model_name>"
            exit 1
        fi
        if wait_for_ollama; then
            pull_model "$2"
        fi
        ;;
    "help")
        echo "Usage: $0 [command]"
        echo
        echo "Commands:"
        echo "  setup    - Setup required models for SwarmAI (default)"
        echo "  test     - Test all required models"
        echo "  list     - List available models"
        echo "  pull     - Pull a specific model"
        echo "  help     - Show this help message"
        echo
        ;;
    *)
        print_error "Unknown command: $1"
        print_info "Use '$0 help' for usage information"
        exit 1
        ;;
esac