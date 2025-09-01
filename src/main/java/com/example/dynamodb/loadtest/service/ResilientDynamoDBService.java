package com.example.dynamodb.loadtest.service;

import com.example.dynamodb.loadtest.model.TestItem;
import com.example.dynamodb.loadtest.model.TestMetrics;
import com.example.dynamodb.loadtest.repository.DynamoDBRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Resilient DynamoDB service that combines circuit breaker pattern and error
 * handling
 * with retry logic for robust DynamoDB operations.
 */
@Service
public class ResilientDynamoDBService {

    private static final Logger logger = LoggerFactory.getLogger(ResilientDynamoDBService.class);

    private final DynamoDBRepository dynamoDBRepository;
    private final ErrorHandler errorHandler;
    private final CircuitBreaker circuitBreaker;

    public ResilientDynamoDBService(DynamoDBRepository dynamoDBRepository,
            ErrorHandler errorHandler,
            CircuitBreaker circuitBreaker) {
        this.dynamoDBRepository = dynamoDBRepository;
        this.errorHandler = errorHandler;
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * Puts an item to DynamoDB with circuit breaker protection and retry logic.
     * 
     * @param item    the item to put
     * @param metrics the metrics object to update
     * @return CompletableFuture with the put item response
     */
    public CompletableFuture<PutItemResponse> putItemWithResilience(TestItem item, TestMetrics metrics) {
        return CompletableFuture.supplyAsync(() -> {
            Instant startTime = Instant.now();

            try {
                // For local development, try direct execution first to avoid unnecessary
                // fallbacks
                PutItemResponse response;
                try {
                    response = executeWithRetry(item);
                    logger.debug("Direct execution successful for item: {}", item.getPrimaryKey());
                } catch (Exception directException) {
                    logger.debug("Direct execution failed, trying with circuit breaker for item: {}",
                            item.getPrimaryKey());
                    // Execute with circuit breaker protection
                    response = circuitBreaker.execute(
                            // Main operation with retry logic
                            () -> executeWithRetry(item),
                            // Fallback operation
                            () -> createFallbackResponse(item));
                }

                // Record success metrics
                Duration responseTime = Duration.between(startTime, Instant.now());
                metrics.setResponseTime(metrics.getResponseTime().plus(responseTime));
                metrics.addSuccess();

                logger.debug("Successfully put item with resilience: {}", item.getPrimaryKey());
                return response;

            } catch (Exception e) {
                // Handle error and update metrics
                Duration responseTime = Duration.between(startTime, Instant.now());
                metrics.setResponseTime(metrics.getResponseTime().plus(responseTime));
                errorHandler.handleDynamoDBError(e, metrics);

                logger.error("Failed to put item with resilience: {}", item.getPrimaryKey(), e);
                throw new RuntimeException("Failed to put item: " + item.getPrimaryKey(), e);
            }
        });
    }

    /**
     * Enhanced version with better duplicate error handling and no fallback for
     * accurate counting.
     * 
     * @param item    the item to put
     * @param metrics the metrics object to update
     * @return CompletableFuture with the put item response
     */
    public CompletableFuture<PutItemResponse> putItemWithEnhancedResilience(TestItem item, TestMetrics metrics) {
        return CompletableFuture.supplyAsync(() -> {
            Instant startTime = Instant.now();

            try {
                // Execute with limited retry for accurate duplicate error counting
                PutItemResponse response = executeWithLimitedRetry(item);

                // Record success metrics
                Duration responseTime = Duration.between(startTime, Instant.now());
                metrics.setResponseTime(metrics.getResponseTime().plus(responseTime));
                metrics.addSuccess();

                logger.debug("Successfully put item with enhanced resilience: {}", item.getPrimaryKey());
                return response;

            } catch (Exception e) {
                // Handle error and update metrics
                Duration responseTime = Duration.between(startTime, Instant.now());
                metrics.setResponseTime(metrics.getResponseTime().plus(responseTime));
                errorHandler.handleDynamoDBError(e, metrics);

                logger.debug("Failed to put item with enhanced resilience: {} - {}",
                        item.getPrimaryKey(), errorHandler.categorizeError(e));
                throw new RuntimeException("Failed to put item: " + item.getPrimaryKey(), e);
            }
        });
    }

    /**
     * Puts an item to DynamoDB with circuit breaker protection only (no fallback).
     * 
     * @param item    the item to put
     * @param metrics the metrics object to update
     * @return CompletableFuture with the put item response
     */
    public CompletableFuture<PutItemResponse> putItemWithCircuitBreaker(TestItem item, TestMetrics metrics) {
        return CompletableFuture.supplyAsync(() -> {
            Instant startTime = Instant.now();

            try {
                // Execute with circuit breaker protection (no fallback)
                PutItemResponse response = circuitBreaker.execute(() -> executeWithRetry(item));

                // Record success metrics
                Duration responseTime = Duration.between(startTime, Instant.now());
                metrics.setResponseTime(metrics.getResponseTime().plus(responseTime));
                metrics.addSuccess();

                logger.debug("Successfully put item with circuit breaker: {}", item.getPrimaryKey());
                return response;

            } catch (Exception e) {
                // Handle error and update metrics
                Duration responseTime = Duration.between(startTime, Instant.now());
                metrics.setResponseTime(metrics.getResponseTime().plus(responseTime));
                errorHandler.handleDynamoDBError(e, metrics);

                logger.error("Failed to put item with circuit breaker: {}", item.getPrimaryKey(), e);
                throw new RuntimeException("Failed to put item: " + item.getPrimaryKey(), e);
            }
        });
    }

    /**
     * Checks if a table exists with circuit breaker protection.
     * 
     * @param tableName the table name to check
     * @return CompletableFuture with boolean result
     */
    public CompletableFuture<Boolean> tableExistsWithResilience(String tableName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return circuitBreaker.execute(
                        // Main operation
                        () -> {
                            try {
                                return dynamoDBRepository.tableExists(tableName).get();
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to check table existence", e);
                            }
                        },
                        // Fallback - assume table exists to allow operations to continue
                        () -> {
                            logger.warn("Circuit breaker open for table existence check, assuming table exists: {}",
                                    tableName);
                            return true;
                        });
            } catch (Exception e) {
                logger.error("Failed to check table existence with resilience: {}", tableName, e);
                // Default to true to allow operations to continue
                return true;
            }
        });
    }

    /**
     * Gets the current circuit breaker state.
     * 
     * @return the circuit breaker state
     */
    public CircuitBreaker.State getCircuitBreakerState() {
        return circuitBreaker.getState();
    }

    /**
     * Gets circuit breaker statistics.
     * 
     * @return circuit breaker statistics
     */
    public CircuitBreaker.CircuitBreakerStats getCircuitBreakerStats() {
        return circuitBreaker.getStats();
    }

    /**
     * Resets the circuit breaker to closed state.
     */
    public void resetCircuitBreaker() {
        circuitBreaker.reset();
        logger.info("Circuit breaker reset for DynamoDB operations");
    }

    /**
     * Forces the circuit breaker to open state.
     */
    public void openCircuitBreaker() {
        circuitBreaker.forceOpen();
        logger.warn("Circuit breaker forced open for DynamoDB operations");
    }

    /**
     * Executes a put item operation with retry logic.
     * 
     * @param item the item to put
     * @return the put item response
     * @throws Exception if the operation fails after all retries
     */
    private PutItemResponse executeWithRetry(TestItem item) throws Exception {
        return errorHandler.executeWithRetry(() -> {
            try {
                return dynamoDBRepository.putItem(item).get();
            } catch (Exception e) {
                // Unwrap CompletionException if present
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                } else if (cause instanceof Exception) {
                    throw (Exception) cause;
                } else {
                    throw new RuntimeException("DynamoDB operation failed", e);
                }
            }
        });
    }

    /**
     * Executes a put item operation with limited retry for accurate duplicate error
     * counting.
     * Only retries on network/timeout errors, not on duplicate key errors.
     * 
     * @param item the item to put
     * @return the put item response
     * @throws Exception if the operation fails after all retries
     */
    private PutItemResponse executeWithLimitedRetry(TestItem item) throws Exception {
        int maxAttempts = 3; // Limited retries for better duplicate error accuracy
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return dynamoDBRepository.putItem(item).get();
            } catch (Exception e) {
                lastException = e;

                // Unwrap CompletionException if present
                Throwable cause = e.getCause();
                Exception actualException = (cause instanceof Exception) ? (Exception) cause : e;

                String errorType = errorHandler.categorizeError(actualException);

                // Don't retry duplicate key errors - they should be counted accurately
                if (TestMetrics.ERROR_TYPE_DUPLICATE_KEY.equals(errorType)) {
                    logger.debug("Duplicate key error detected, not retrying: {}", item.getPrimaryKey());
                    throw actualException;
                }

                // Only retry on network/timeout/capacity errors
                if (!errorHandler.shouldRetry(actualException, attempt) || attempt >= maxAttempts) {
                    break;
                }

                Duration delay = errorHandler.calculateBackoff(attempt);
                logger.debug("Retry attempt {} for item {} after {} ms: {}",
                        attempt, item.getPrimaryKey(), delay.toMillis(), errorType);

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
     * Creates a fallback response when the circuit breaker is open.
     * This is a mock response to allow the application to continue functioning
     * when DynamoDB is unavailable.
     * 
     * @param item the item that was supposed to be put
     * @return a mock PutItemResponse
     */
    private PutItemResponse createFallbackResponse(TestItem item) {
        logger.warn("Using fallback response for item: {}", item.getPrimaryKey());

        // Create a mock response - in a real scenario, you might:
        // 1. Store the item in a local cache/queue for later processing
        // 2. Write to an alternative storage system
        // 3. Return a response indicating degraded service

        return PutItemResponse.builder()
                .build();
    }

    /**
     * Checks if the service is currently available (circuit breaker is not open).
     * 
     * @return true if the service is available
     */
    public boolean isServiceAvailable() {
        return circuitBreaker.canExecute();
    }

    /**
     * Gets the underlying DynamoDB repository.
     * 
     * @return the DynamoDB repository
     */
    public DynamoDBRepository getDynamoDBRepository() {
        return dynamoDBRepository;
    }

    /**
     * Gets the error handler.
     * 
     * @return the error handler
     */
    public ErrorHandler getErrorHandler() {
        return errorHandler;
    }

    /**
     * Gets the circuit breaker.
     * 
     * @return the circuit breaker
     */
    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }
}