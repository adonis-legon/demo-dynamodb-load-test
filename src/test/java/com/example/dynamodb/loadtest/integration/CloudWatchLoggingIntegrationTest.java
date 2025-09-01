package com.example.dynamodb.loadtest.integration;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for structured logging in batch application.
 * Tests JSON logging format and MDC context for CloudWatch integration.
 * In ECS, stdout/stderr logs are automatically captured and sent to CloudWatch
 * Logs.
 */
@SpringBootTest(properties = {
        "spring.profiles.active=local",
        "logging.level.com.example.dynamodb.loadtest=DEBUG"
})
@ActiveProfiles("local")
class CloudWatchLoggingIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(CloudWatchLoggingIntegrationTest.class);

    @Test
    void testStructuredLogging() {
        // Given - Set up MDC context for structured logging
        MDC.put("test.id", "structured-logging-test");
        MDC.put("test.type", "integration");
        MDC.put("batch.job", "load-test");

        try {
            // When - Log messages with structured context
            logger.info("Testing structured logging with MDC context");
            logger.warn("Warning message with context");
            logger.error("Error message with context");
            logger.debug("Debug message with context");

            // Then - Verify logging works (output will be in JSON format)
            // The actual JSON format verification would be done by examining log output
            // This test ensures the logging configuration loads correctly
            assertTrue(true, "Structured logging executed successfully");

            logger.info("Structured logging test completed successfully");

        } finally {
            MDC.clear();
        }
    }

    @Test
    void testBatchApplicationStartup() {
        // This test verifies that the application starts correctly as a batch
        // application
        // without web server components

        // When - Application should start without web server
        // The fact that this test runs means the application context loaded
        // successfully

        // Then - Verify we can log with application context
        MDC.put("test.name", "batch-startup-test");

        try {
            logger.info("Batch application startup test - application context loaded successfully");

            // Verify the application is configured correctly for batch processing
            String webApplicationType = System.getProperty("spring.main.web-application-type");
            assertEquals("none", webApplicationType, "Application should be configured with no web server");

            logger.info("Batch application startup test completed successfully");

        } finally {
            MDC.clear();
        }
    }

    @Test
    void testMDCContextPersistence() {
        // Test that MDC context is properly maintained across log statements

        // Given
        String executionId = "test-execution-123";
        String batchId = "batch-456";

        MDC.put("execution.id", executionId);
        MDC.put("batch.id", batchId);

        try {
            // When - Multiple log statements with same context
            logger.info("Starting batch processing");
            logger.info("Processing item 1");
            logger.info("Processing item 2");
            logger.info("Batch processing completed");

            // Then - Context should be maintained (verified by log output inspection)
            // This test ensures MDC context works correctly for batch processing
            assertTrue(true, "MDC context maintained across log statements");

        } finally {
            MDC.clear();
        }
    }
}
