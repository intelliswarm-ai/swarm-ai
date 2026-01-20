FROM eclipse-temurin:21-jdk-jammy

# Install curl for health checks
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /app

# Copy Maven wrapper and pom.xml
COPY mvnw .
COPY mvnw.cmd .
COPY .mvn .mvn
COPY pom.xml .

# Make mvnw executable
RUN chmod +x mvnw

# Copy source code
COPY src src

# Build the application
RUN ./mvnw clean package -DskipTests

# Create entrypoint script
RUN echo '#!/bin/bash\n\
echo "Starting Stock Analysis Workflow..."\n\
echo "Using Ollama at: $SPRING_AI_OLLAMA_BASE_URL"\n\
echo "Analyzing stock: $STOCK_TICKER"\n\
echo ""\n\
# Wait for Ollama to be ready\n\
until curl -f "$SPRING_AI_OLLAMA_BASE_URL/api/version" > /dev/null 2>&1; do\n\
  echo "Waiting for Ollama to be ready..."\n\
  sleep 5\n\
done\n\
echo "Ollama is ready!"\n\
echo ""\n\
# Run the application\n\
exec java -jar target/swarmai-framework-*.jar stock-analysis $STOCK_TICKER' > /app/entrypoint.sh

RUN chmod +x /app/entrypoint.sh

# Expose application port
EXPOSE 8080

# Set entrypoint
ENTRYPOINT ["/app/entrypoint.sh"]