# SwarmAI Iterative Investment Memo Example - Dockerized
#
# Demonstrates the ITERATIVE process type: agents execute tasks,
# a reviewer evaluates output against a quality rubric, and tasks
# re-execute with feedback until approved or max iterations reached.

FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="IntelliSwarm.ai"
LABEL description="SwarmAI Iterative Investment Memo - Execute/Review/Refine Loop"
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
COPY examples/iterative/config/ config/

# Copy startup script
COPY examples/iterative/docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

# Set environment variables
ENV SPRING_PROFILES_ACTIVE=docker
ENV SPRING_AI_OLLAMA_BASE_URL=http://ollama:11434
ENV SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL=llama3.2:3b
ENV SPRING_AI_OLLAMA_CHAT_OPTIONS_TEMPERATURE=0.7
ENV SWARMAI_DEFAULT_VERBOSE=true
ENV JAVA_OPTS="-Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+UseStringDeduplication"

# Iterative-specific defaults
ENV STOCK_TICKER=NVDA
ENV MAX_ITERATIONS=3

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Expose port
EXPOSE 8080

# Use the entrypoint script
ENTRYPOINT ["/app/docker-entrypoint.sh"]

# Default command runs the iterative investment memo
CMD ["iterative-memo"]
