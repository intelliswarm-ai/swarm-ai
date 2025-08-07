#!/bin/bash

# SwarmAI Research Example - Docker Build Script
# This script prepares the build context and builds the Docker image

set -e

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_info() {
    echo -e "${BLUE}ℹ️  $1${NC}"
}

print_success() {
    echo -e "${GREEN}✅ $1${NC}"
}

print_info "🏗️ Building SwarmAI Research Docker Image"

# Create build directory
BUILD_DIR="docker-build"
rm -rf $BUILD_DIR
mkdir -p $BUILD_DIR

print_info "Preparing build context..."

# Copy JAR file
cp ../../target/swarmai-framework-*.jar $BUILD_DIR/app.jar

# Copy application configuration
cp ../../src/main/resources/application-docker.yml $BUILD_DIR/application.yml

# Copy research-specific files
cp -r config $BUILD_DIR/
cp docker-entrypoint.sh $BUILD_DIR/
chmod +x $BUILD_DIR/docker-entrypoint.sh

# Create simplified Dockerfile for build
cat > $BUILD_DIR/Dockerfile << 'EOF'
# SwarmAI Research Example - Simplified Docker Build
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="IntelliSwarm.ai"
LABEL description="SwarmAI Research Example - Competitive Analysis Workflow"
LABEL version="1.0.0"

# Install required packages
RUN apk add --no-cache \
    curl \
    bash \
    && rm -rf /var/cache/apk/*

# Create application directory
WORKDIR /app

# Create directories for outputs and logs
RUN mkdir -p /app/reports /app/logs /app/config

# Copy application files
COPY app.jar app.jar
COPY application.yml application.yml
COPY config/ config/
COPY docker-entrypoint.sh docker-entrypoint.sh
RUN chmod +x docker-entrypoint.sh

# Set environment variables for the research example
ENV SPRING_PROFILES_ACTIVE=docker
ENV SPRING_AI_OLLAMA_BASE_URL=http://ollama:11434
ENV SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL=llama3.2:3b
ENV SPRING_AI_OLLAMA_CHAT_OPTIONS_TEMPERATURE=0.7
ENV SWARMAI_DEFAULT_VERBOSE=true
ENV JAVA_OPTS="-Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=100"

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Expose port
EXPOSE 8080

# Use the entrypoint script
ENTRYPOINT ["./docker-entrypoint.sh"]

# Default command runs the competitive analysis
CMD ["competitive-analysis"]
EOF

print_success "Build context prepared"

# Build the image
print_info "Building Docker image..."
docker build -t swarmai-research:latest $BUILD_DIR

if [ $? -eq 0 ]; then
    print_success "Docker image built successfully!"
    print_info "Image: swarmai-research:latest"
    
    # Clean up build directory
    rm -rf $BUILD_DIR
    print_success "Build context cleaned up"
else
    print_error "Docker build failed"
    exit 1
fi