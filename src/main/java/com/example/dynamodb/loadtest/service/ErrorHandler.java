package com.example.dynamodb.loadtest.service;

import com.example.dynamodb.loadtest.model.TestMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Component responsible for categorizing and handling DynamoDB errors.
 * Implements retry logic with exponential backoff for capacity errors.
 */
@Component
public class ErrorHandler {

    private static final Logger logger = LoggerFactory.getLogger(ErrorHandler.class);

    // Retry configuration constants - optimized for accurate duplicate error
    // counting
    private static final int MAX_RETRY_ATTEMPTS = 3; // Limited retries for better accuracy
    private static final Duration BASE_DELAY = Duration.ofMillis(50); // Small delay for capacity errors
    private static final Duration MAX_DELAY = Duration.ofMillis(1000); // Max 1 second delay
    private static final double JITTER_FACTOR = 0.1; // Small jitter to avoid thundering herd

    /**
     * Handles DynamoDB errors by categorizing them and updating metrics.
     * 
     * @param exception the exception that occurred
     * @param metrics   the metrics object to update
     */
    public void handleDynamoDBError(Exception exception, TestMetrics metrics) {
        if (exception == null || metrics == null) {
            return;
        }

        String errorType = categorizeError(exception);
        metrics.addError(errorType);

        logger.debug("Categorized error as '{}': {}", errorType, exception.getMessage());
    }

    /**
     * Categorizes an exception into a specific error type.
     * 
     * @param exception the exception to categorize
     * @return the error type string
     */
    public String categorizeError(Exception exception) {
        if (exception == null) {
            return TestMetrics.ERROR_TYPE_UNKNOWN;
        }

        // Handle DynamoDB specific exceptions
        if (exception instanceof ProvisionedThroughputExceededException ||
                exception instanceof RequestLimitExceededException) {
            return TestMetrics.ERROR_TYPE_CAPACITY_EXCEEDED;
        }

        if (exception instanceof ConditionalCheckFailedException) {
            return TestMetrics.ERROR_TYPE_DUPLICATE_KEY;
        }

        // Handle timeout and network errors (check timeout first as it's more specific)
        if (isTimeoutError(exception)) {
            return TestMetrics.ERROR_TYPE_TIMEOUT;
        }

        if (isNetworkError(exception)) {
            return TestMetrics.ERROR_TYPE_NETWORK;
        }

        // Handle generic DynamoDB exceptions
        if (exception instanceof DynamoDbException) {
            DynamoDbException dynamoException = (DynamoDbException) exception;

            // Check error code for additional categorization
            String errorCode = dynamoException.awsErrorDetails() != null ? dynamoException.awsErrorDetails().errorCode()
                    : null;

            if ("ThrottlingException".equals(errorCode) || "Throttling".equals(errorCode)) {
                return TestMetrics.ERROR_TYPE_THROTTLING;
            }

            if ("ProvisionedThroughputExceededException".equals(errorCode)) {
                return TestMetrics.ERROR_TYPE_CAPACITY_EXCEEDED;
            }

            if ("ConditionalCheckFailedException".equals(errorCode)) {
                return TestMetrics.ERROR_TYPE_DUPLICATE_KEY;
            }

            if ("ValidationException".equals(errorCode)) {
                return TestMetrics.ERROR_TYPE_VALIDATION;
            }
        }

        return TestMetrics.ERROR_TYPE_UNKNOWN;
    }

    /**
     * Determines if an error should be retried based on the error type and attempt
     * count. Enhanced for accurate duplicate error counting.
     * 
     * @param exception    the exception that occurred
     * @param attemptCount the current attempt count (1-based)
     * @return true if the operation should be retried
     */
    public boolean shouldRetry(Exception exception, int attemptCount) {
        if (exception == null || attemptCount >= MAX_RETRY_ATTEMPTS) {
            return false;
        }

        String errorType = categorizeError(exception);

        // NEVER retry duplicate key errors - they should be counted accurately
        if (TestMetrics.ERROR_TYPE_DUPLICATE_KEY.equals(errorType)) {
            return false;
        }

        // Only retry transient errors that don't affect duplicate counting accuracy
        return TestMetrics.ERROR_TYPE_CAPACITY_EXCEEDED.equals(errorType) ||
                TestMetrics.ERROR_TYPE_THROTTLING.equals(errorType) ||
                TestMetrics.ERROR_TYPE_NETWORK.equals(errorType) ||
                TestMetrics.ERROR_TYPE_TIMEOUT.equals(errorType);
    }

    /**
     * Calculates the backoff delay for retry attempts using exponential backoff
     * with jitter.
     * 
     * @param attemptCount the current attempt count (1-based)
     * @return the delay duration before the next retry
     */
    public Duration calculateBackoff(int attemptCount) {
        if (attemptCount <= 0) {
            return BASE_DELAY;
        }

        // Exponential backoff: base_delay * 2^(attempt - 1)
        long delayMs = BASE_DELAY.toMillis() * (1L << Math.min(attemptCount - 1, 10)); // Cap at 2^10

        // Apply maximum delay limit
        delayMs = Math.min(delayMs, MAX_DELAY.toMillis());

        // Add jitter to avoid thundering herd
        double jitter = 1.0 + (ThreadLocalRandom.current().nextDouble() - 0.5) * 2 * JITTER_FACTOR;
        delayMs = (long) (delayMs * jitter);

        // Ensure the final delay respects both minimum and maximum limits
        delayMs = Math.max(delayMs, BASE_DELAY.toMillis());
        delayMs = Math.min(delayMs, MAX_DELAY.toMillis());

        return Duration.ofMillis(delayMs);
    }

    /**
     * Executes a retry operation with exponential backoff.
     * 
     * @param operation   the operation to retry
     * @param maxAttempts maximum number of attempts
     * @param <T>         the return type of the operation
     * @return the result of the operation
     * @throws Exception if all retry attempts fail
     */
    public <T> T executeWithRetry(RetryableOperation<T> operation, int maxAttempts) throws Exception {
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return operation.execute();
            } catch (Exception e) {
                lastException = e;

                if (!shouldRetry(e, attempt) || attempt >= maxAttempts) {
                    break;
                }

                Duration delay = calculateBackoff(attempt);
                logger.debug("Retry attempt {} failed, delay {} ms before next attempt: {}",
                        attempt, delay.toMillis(), e.getMessage());

                // Skip sleep for load testing when delay is 0
                if (delay.toMillis() > 0) {
                    try {
                        Thread.sleep(delay.toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Retry interrupted", ie);
                    }
                }
            }
        }

        throw lastException != null ? lastException
                : new RuntimeException("Operation failed after " + maxAttempts + " attempts");
    }

    /**
     * Executes a retry operation with default maximum attempts.
     * 
     * @param operation the operation to retry
     * @param <T>       the return type of the operation
     * @return the result of the operation
     * @throws Exception if all retry attempts fail
     */
    public <T> T executeWithRetry(RetryableOperation<T> operation) throws Exception {
        return executeWithRetry(operation, MAX_RETRY_ATTEMPTS);
    }

    /**
     * Checks if an exception is a network-related error.
     * 
     * @param exception the exception to check
     * @return true if it's a network error
     */
    private boolean isNetworkError(Exception exception) {
        if (exception == null) {
            return false;
        }

        String message = exception.getMessage();
        if (message == null) {
            message = "";
        }

        String lowerMessage = message.toLowerCase();

        return exception instanceof java.net.ConnectException ||
                exception instanceof java.net.UnknownHostException ||
                exception instanceof java.net.NoRouteToHostException ||
                exception instanceof java.io.IOException ||
                lowerMessage.contains("connection") ||
                lowerMessage.contains("network") ||
                lowerMessage.contains("host") ||
                lowerMessage.contains("unreachable");
    }

    /**
     * Checks if an exception is a timeout-related error.
     * 
     * @param exception the exception to check
     * @return true if it's a timeout error
     */
    private boolean isTimeoutError(Exception exception) {
        if (exception == null) {
            return false;
        }

        String message = exception.getMessage();
        if (message == null) {
            message = "";
        }

        String lowerMessage = message.toLowerCase();

        return exception instanceof java.net.SocketTimeoutException ||
                exception instanceof java.util.concurrent.TimeoutException ||
                lowerMessage.contains("timeout") ||
                lowerMessage.contains("timed out");
    }

    /**
     * Gets the maximum number of retry attempts.
     * 
     * @return the maximum retry attempts
     */
    public int getMaxRetryAttempts() {
        return MAX_RETRY_ATTEMPTS;
    }

    /**
     * Gets the base delay for retry operations.
     * 
     * @return the base delay duration
     */
    public Duration getBaseDelay() {
        return BASE_DELAY;
    }

    /**
     * Gets the maximum delay for retry operations.
     * 
     * @return the maximum delay duration
     */
    public Duration getMaxDelay() {
        return MAX_DELAY;
    }

    /**
     * Functional interface for retryable operations.
     * 
     * @param <T> the return type of the operation
     */
    @FunctionalInterface
    public interface RetryableOperation<T> {
        T execute() throws Exception;
    }
}