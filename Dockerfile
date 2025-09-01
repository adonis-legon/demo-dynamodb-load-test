# Use Eclipse Temurin OpenJDK 21 (ARM64/AMD64 compatible)
FROM eclipse-temurin:21-jdk-alpine

# Set working directory
WORKDIR /app

# Copy the JAR file
COPY target/dynamodb-load-test-*.jar app.jar

# Create a non-root user (Alpine Linux compatible)
RUN addgroup -S appuser && adduser -S appuser -G appuser
RUN chown -R appuser:appuser /app
USER appuser

# Expose port (though this is a console app, keeping for potential future use)
EXPOSE 8080

# Set JVM options for container environment
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 --enable-preview"

# Health check (basic JVM process check)
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD ps aux | grep -q "[j]ava.*app.jar" || exit 1

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]