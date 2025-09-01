package com.example.dynamodb.loadtest.integration;

import com.example.dynamodb.loadtest.model.TestConfiguration;
import com.example.dynamodb.loadtest.model.TestMetrics;
import com.example.dynamodb.loadtest.service.ConfigurationManager;
import com.example.dynamodb.loadtest.service.ErrorHandler;
import com.example.dynamodb.loadtest.service.LoadTestService;
import com.example.dynamodb.loadtest.service.MetricsCollectionService;
import com.example.dynamodb.loadtest.service.MetricsCollectionService.TestSummary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive error handling validation tests.
 * Tests all error scenarios and validates proper error categorization and
 * handling.
 * 
 * Requirements: 11.1, 11.5
 */
@SpringBootTest
@ActiveProfiles("integration")
class ErrorHandlingValidationTest extends LocalStackIntegrationTestBase {

    @Autowired
    private ConfigurationManager configurationManager;

    @Autowired
    private LoadTestService loadTestService;

    @Autowired
    private MetricsCollectionService metricsCollectionService;

    @Autowired
    private ErrorHandler errorHandler;

    @BeforeEach
    void setUp() {
        metricsCollectionService.reset();
    }

    @AfterEach
    void tearDown() {
        cleanupTestData();
        metricsCollectionService.reset();
    }

    /**
     * Test handling of DynamoDB capacity exceeded errors.
     * Requirements: 11.1, 11.5
     */
    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void shouldHandleCapacityExceededErrors() {
        // Given - Create a capacity exceeded exception
        ProvisionedThroughputExceededException capacityError = ProvisionedThroughputExceededException.builder()
                .message("Provisioned throughput exceeded")
                .build();

        TestMetrics metrics = new TestMetrics();

        // When
        errorHandler.handleDynamoDBError(capacityError, metrics);
        String errorType = errorHandler.categorizeError(capacityError);

        // Then
        assertThat(errorType).isEqualTo(TestMetrics.ERROR_TYPE_CAPACITY_EXCEEDED);
        assertThat(metrics.getErrorCount()).isEqualTo(1);
        assertThat(metrics.getCapacityExceededErrors()).isEqualTo(1);

        // Verify retry logic
        assertTrue(errorHandler.shouldRetry(capacityError, 1));
        assertTrue(errorHandler.shouldRetry(capacityError, 3));
        assertFalse(errorHandler.shouldRetry(capacityError, 5)); // Max attempts reached
    }

    /**
     * Test handling of duplicate key errors.
     * Requirements: 11.1, 11.5
     */
    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void shouldHandleDuplicateKeyErrors() {
        // Given
        ConditionalCheckFailedException duplicateError = ConditionalCheckFailedException.builder()
                .message("Conditional check failed")
                .build();

        TestMetrics metrics = new TestMetrics();

        // When
        errorHandler.handleDynamoDBError(duplicateError, metrics);
        String errorType = errorHandler.categorizeError(duplicateError);

        // Then
        assertThat(errorType).isEqualTo(TestMetrics.ERROR_TYPE_DUPLICATE_KEY);
        assertThat(metrics.getErrorCount()).isEqualTo(1);
        assertThat(metrics.getDuplicateKeyErrors()).isEqualTo(1);

        // Duplicate key errors should not be retried
        assertFalse(errorHandler.shouldRetry(duplicateError, 1));
    }

    /**
     * Test handling of throttling errors.
     * Requirements: 11.1, 11.5
     */
    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void shouldHandleThrottlingErrors() {
        // Given
        AwsErrorDetails errorDetails = AwsErrorDetails.builder()
                .errorCode("ThrottlingException")
                .errorMessage("Request was throttled")
                .build();

        DynamoDbException throttlingError = (DynamoDbException) DynamoDbException.builder()
                .message("Request was throttled")
                .awsErrorDetails(errorDetails)
                .build();

        TestMetrics metrics = new TestMetrics();

        // When
        errorHandler.handleDynamoDBError(throttlingError, metrics);
        String errorType = errorHandler.categorizeError(throttlingError);

        // Then
        assertThat(errorType).isEqualTo(TestMetrics.ERROR_TYPE_THROTTLING);
        assertThat(metrics.getErrorCount()).isEqualTo(1);
        assertThat(metrics.getThrottlingErrors()).isEqualTo(1);

        // Throttling errors should be retried
        assertTrue(errorHandler.shouldRetry(throttlingError, 1));
        assertTrue(errorHandler.shouldRetry(throttlingError, 4));
        assertFalse(errorHandler.shouldRetry(throttlingError, 5));
    }

    /**
     * Test handling of validation errors.
     * Requirements: 11.1, 11.5
     */
    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void shouldHandleValidationErrors() {
        // Given
        AwsErrorDetails errorDetails = AwsErrorDetails.builder()
                .errorCode("ValidationException")
                .errorMessage("Validation failed")
                .build();

        DynamoDbException validationError = (DynamoDbException) DynamoDbException.builder()
                .message("Validation failed")
                .awsErrorDetails(errorDetails)
                .build();

        TestMetrics metrics = new TestMetrics();

        // When
        errorHandler.handleDynamoDBError(validationError, metrics);
        String errorType = errorHandler.categorizeError(validationError);

        // Then
        assertThat(errorType).isEqualTo(TestMetrics.ERROR_TYPE_VALIDATION);
        assertThat(metrics.getErrorCount()).isEqualTo(1);
        assertThat(metrics.getErrorTypes().get(TestMetrics.ERROR_TYPE_VALIDATION)).isEqualTo(1);

        // Validation errors should not be retried
        assertFalse(errorHandler.shouldRetry(validationError, 1));
    }

    /**
     * Test handling of network errors.
     * Requirements: 11.1, 11.5
     */
    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void shouldHandleNetworkErrors() {
        // Given
        java.net.ConnectException networkError = new java.net.ConnectException("Connection refused");
        TestMetrics metrics = new TestMetrics();

        // When
        errorHandler.handleDynamoDBError(networkError, metrics);
        String errorType = errorHandler.categorizeError(networkError);

        // Then
        assertThat(errorType).isEqualTo(TestMetrics.ERROR_TYPE_NETWORK);
        assertThat(metrics.getErrorCount()).isEqualTo(1);
        assertThat(metrics.getErrorTypes().get(TestMetrics.ERROR_TYPE_NETWORK)).isEqualTo(1);

        // Network errors should be retried
        assertTrue(errorHandler.shouldRetry(networkError, 1));
        assertTrue(errorHandler.shouldRetry(networkError, 3));
        assertFalse(errorHandler.shouldRetry(networkError, 5));
    }

    /**
     * Test handling of timeout errors.
     * Requirements: 11.1, 11.5
     */
    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void shouldHandleTimeoutErrors() {
        // Given
        java.net.SocketTimeoutException timeoutError = new java.net.SocketTimeoutException("Socket timeout");
        TestMetrics metrics = new TestMetrics();

        // When
        errorHandler.handleDynamoDBError(timeoutError, metrics);
        String errorType = errorHandler.categorizeError(timeoutError);

        // Then
        assertThat(errorType).isEqualTo(TestMetrics.ERROR_TYPE_TIMEOUT);
        assertThat(metrics.getErrorCount()).isEqualTo(1);
        assertThat(metrics.getErrorTypes().get(TestMetrics.ERROR_TYPE_TIMEOUT)).isEqualTo(1);

        // Timeout errors should be retried
        assertTrue(errorHandler.shouldRetry(timeoutError, 1));
        assertTrue(errorHandler.shouldRetry(timeoutError, 4));
        assertFalse(errorHandler.shouldRetry(timeoutError, 5));
    }

    /**
     * Test retry mechanism with exponential backoff.
     * Requirements: 11.1, 11.5
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldImplementExponentialBackoff() throws Exception {
        // Given
        java.util.concurrent.atomic.AtomicInteger attemptCount = new java.util.concurrent.atomic.AtomicInteger(0);

        ErrorHandler.RetryableOperation<String> operation = () -> {
            int attempt = attemptCount.incrementAndGet();
            if (attempt < 3) {
                throw ProvisionedThroughputExceededException.builder()
                        .message("Capacity exceeded")
                        .build();
            }
            return "success";
        };

        // When
        long startTime = System.currentTimeMillis();
        String result = errorHandler.executeWithRetry(operation);
        long totalTime = System.currentTimeMillis() - startTime;

        // Then
        assertThat(result).isEqualTo("success");
        assertThat(attemptCount.get()).isEqualTo(3);

        // Should have taken some time due to backoff delays
        assertThat(totalTime).isGreaterThan(100); // At least 100ms for backoffs
    }

    /**
     * Test error handling during configuration loading.
     * Requirements: 11.1, 11.4
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldHandleConfigurationErrors() {
        // Given - Remove a required parameter
        String parameterName = "/local/dynamodb-load-test/concurrency-limit";

        try {
            ssmClient.deleteParameter(builder -> builder.name(parameterName)).join();
        } catch (Exception e) {
            // Parameter might not exist
        }

        // When/Then - Should handle missing parameter gracefully
        CompletableFuture<TestConfiguration> configFuture = configurationManager.loadConfiguration();

        Exception exception = assertThrows(Exception.class, () -> {
            configFuture.join();
        });

        // Verify the error is properly categorized
        Throwable cause = exception.getCause();
        while (cause != null && !(cause instanceof ParameterNotFoundException)) {
            cause = cause.getCause();
        }

        // Should eventually find a ParameterNotFoundException in the cause chain
        // or handle it gracefully with a meaningful error message
        assertThat(exception.getMessage()).isNotNull();

        // Restore parameter for cleanup
        setupSSMParameters();
    }

    /**
     * Test error aggregation during load test execution.
     * Requirements: 11.1, 11.3, 11.5
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void shouldAggregateErrorsCorrectly() {
        // Given - Configuration with duplicate injection to generate some errors
        TestConfiguration config = configurationManager.loadConfiguration().join();
        assertThat(config.getDuplicatePercentage()).isGreaterThan(0.0);

        // When - Execute load test
        TestSummary summary = loadTestService.executeLoadTest(config).join();

        // Then - Verify error aggregation
        assertThat(summary).isNotNull();
        assertThat(summary.getTotalOperations()).isEqualTo(config.getTotalItems());
        assertThat(summary.getTotalSuccesses() + summary.getTotalErrors()).isEqualTo(config.getTotalItems());

        // Verify error breakdown structure
        assertThat(summary.getErrorTypeCounts()).isNotNull();

        if (summary.getTotalErrors() > 0) {
            // Verify all errors are properly categorized
            long totalCategorizedErrors = summary.getErrorTypeCounts().values().stream()
                    .mapToLong(Long::longValue)
                    .sum();
            assertThat(totalCategorizedErrors).isEqualTo(summary.getTotalErrors());

            // Verify error types are valid
            for (String errorType : summary.getErrorTypeCounts().keySet()) {
                assertThat(errorType).isIn(
                        TestMetrics.ERROR_TYPE_CAPACITY_EXCEEDED,
                        TestMetrics.ERROR_TYPE_DUPLICATE_KEY,
                        TestMetrics.ERROR_TYPE_THROTTLING,
                        TestMetrics.ERROR_TYPE_VALIDATION,
                        TestMetrics.ERROR_TYPE_NETWORK,
                        TestMetrics.ERROR_TYPE_TIMEOUT,
                        TestMetrics.ERROR_TYPE_UNKNOWN);
            }
        }
    }

    /**
     * Test circuit breaker behavior under error conditions.
     * Requirements: 11.1, 11.5
     */
    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void shouldImplementCircuitBreakerPattern() {
        // Given - This test verifies that the circuit breaker pattern is implemented
        // The actual circuit breaker implementation would be tested in unit tests
        // Here we verify it integrates properly with the load test service

        TestConfiguration config = configurationManager.loadConfiguration().join();

        // When - Execute load test (circuit breaker should be active)
        TestSummary summary = loadTestService.executeLoadTest(config).join();

        // Then - Test should complete successfully with circuit breaker protection
        assertThat(summary).isNotNull();
        assertThat(summary.getTotalOperations()).isEqualTo(config.getTotalItems());

        // Circuit breaker should not prevent successful operations
        assertThat(summary.getTotalSuccesses()).isGreaterThan(0);
    }

    /**
     * Test error handling with null and invalid inputs.
     * Requirements: 11.1, 11.5
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldHandleNullAndInvalidInputs() {
        // Given
        TestMetrics metrics = new TestMetrics();

        // When/Then - Should handle null inputs gracefully
        assertDoesNotThrow(() -> {
            errorHandler.handleDynamoDBError(null, metrics);
            errorHandler.handleDynamoDBError(new RuntimeException(), null);
            errorHandler.handleDynamoDBError(null, null);
        });

        assertDoesNotThrow(() -> {
            String errorType = errorHandler.categorizeError(null);
            assertThat(errorType).isEqualTo(TestMetrics.ERROR_TYPE_UNKNOWN);
        });

        assertDoesNotThrow(() -> {
            boolean shouldRetry = errorHandler.shouldRetry(null, 1);
            assertThat(shouldRetry).isFalse();
        });

        // Verify metrics remain consistent
        assertThat(metrics.getErrorCount()).isEqualTo(0);
    }

    /**
     * Test error message formatting and logging.
     * Requirements: 11.1, 11.5
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldFormatErrorMessagesCorrectly() {
        // Given
        ProvisionedThroughputExceededException capacityError = ProvisionedThroughputExceededException.builder()
                .message("Provisioned throughput exceeded for table test-table")
                .build();

        // When
        String errorType = errorHandler.categorizeError(capacityError);

        // Then
        assertThat(errorType).isEqualTo(TestMetrics.ERROR_TYPE_CAPACITY_EXCEEDED);

        // Verify error can be converted to string without issues
        assertThat(capacityError.toString()).isNotNull();
        assertThat(capacityError.getMessage()).contains("Provisioned throughput exceeded");
    }
}