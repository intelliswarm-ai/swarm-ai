# SwarmAI Framework Dockerfile
# 
# Multi-stage build for optimal image size and security
# Based on OpenJDK 21 with Spring Boot optimizations

# Build stage
FROM eclipse-temurin:21-jdk-alpine AS builder

# Set working directory
WORKDIR /app

# Install Maven and copy pom.xml for dependency caching
RUN apk add --no-cache maven

COPY pom.xml .

# Download dependencies
RUN mvn dependency:go-offline -B

# Copy source code
COPY src src

# Build the application
RUN mvn clean package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:21-jre-alpine AS runtime

# Create non-root user for security
RUN addgroup -g 1001 swarmai && \
    adduser -D -s /bin/sh -u 1001 -G swarmai swarmai

# Set working directory
WORKDIR /app

# Create directories for logs, reports, and configuration
RUN mkdir -p logs reports config && \
    chown -R swarmai:swarmai /app

# Install curl for health checks
RUN apk add --no-cache curl

# Copy the JAR from builder stage
COPY --from=builder /app/target/swarmai-framework-*.jar app.jar

# Copy configuration files
COPY src/main/resources/application*.yml config/

# Set proper ownership
RUN chown -R swarmai:swarmai /app

# Switch to non-root user
USER swarmai

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# JVM optimization environment variables
ENV JAVA_OPTS="-Xmx1g -Xms512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+UseStringDeduplication"

# Spring Boot configuration
ENV SPRING_PROFILES_ACTIVE=docker
ENV SPRING_CONFIG_LOCATION=/app/config/

# Set timezone
ENV TZ=UTC

# Entry point with JVM optimization
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

# Labels for metadata
LABEL maintainer="IntelliSwarm.ai <team@intelliswarm.ai>" \
      version="1.0.0-SNAPSHOT" \
      description="SwarmAI Framework - Multi-Agent AI Orchestration" \
      org.opencontainers.image.title="SwarmAI Framework" \
      org.opencontainers.image.description="Java multi-agent framework inspired by CrewAI" \
      org.opencontainers.image.vendor="IntelliSwarm.ai" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.source="https://github.com/intelliswarm/swarmai"