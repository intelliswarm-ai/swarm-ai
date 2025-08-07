# SwarmAI Research Example - Dockerized Competitive Analysis
# 
# This Dockerfile creates a focused container for running the competitive
# analysis research workflow with minimal dependencies and optimized performance.

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

# Copy the built JAR file (build context from project root)
COPY target/swarmai-framework-*.jar app.jar

# Copy configuration files
COPY src/main/resources/application-docker.yml application.yml
COPY examples/research/config/ config/

# Copy startup script
COPY examples/research/docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

# Set environment variables for the research example
ENV SPRING_PROFILES_ACTIVE=docker
ENV SPRING_AI_OLLAMA_BASE_URL=http://ollama:11434
ENV SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL=llama3.2:3b
ENV SPRING_AI_OLLAMA_CHAT_OPTIONS_TEMPERATURE=0.7
ENV SWARMAI_DEFAULT_VERBOSE=true
ENV JAVA_OPTS="-Xmx1g -XX:+UseG1GC -XX:MaxGCPauseMillis=100"

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Expose port
EXPOSE 8080

# Use the entrypoint script
ENTRYPOINT ["/app/docker-entrypoint.sh"]

# Default command runs the competitive analysis
CMD ["competitive-analysis"]