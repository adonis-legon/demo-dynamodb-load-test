package com.example.dynamodb.loadtest.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit breaker implementation for DynamoDB operations.
 * Provides fallback mechanisms for service unavailability and prevents
 * cascading failures.
 */
@Component
public class CircuitBreaker {

    private static final Logger logger = LoggerFactory.getLogger(CircuitBreaker.class);

    // Circuit breaker states
    public enum State {
        CLOSED, // Normal operation
        OPEN, // Circuit is open, failing fast
        HALF_OPEN // Testing if service is back
    }

    // Configuration constants - Made less sensitive for LocalStack
    private static final int DEFAULT_FAILURE_THRESHOLD = 10; // Increased from 5
    private static final int DEFAULT_SUCCESS_THRESHOLD = 2; // Decreased from 3
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30); // Decreased from 60
    private static final int DEFAULT_MAX_HALF_OPEN_CALLS = 5; // Increased from 3

    // Circuit breaker state
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger halfOpenCalls = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);

    // Configuration
    private final int failureThreshold;
    private final int successThreshold;
    private final Duration timeout;
    private final int maxHalfOpenCalls;

    /**
     * Creates a circuit breaker with default configuration.
     */
    public CircuitBreaker() {
        this(DEFAULT_FAILURE_THRESHOLD, DEFAULT_SUCCESS_THRESHOLD, DEFAULT_TIMEOUT, DEFAULT_MAX_HALF_OPEN_CALLS);
    }

    /**
     * Creates a circuit breaker with custom configuration.
     * 
     * @param failureThreshold number of failures before opening the circuit
     * @param successThreshold number of successes needed to close the circuit from
     *                         half-open
     * @param timeout          duration to wait before transitioning from open to
     *                         half-open
     * @param maxHalfOpenCalls maximum number of calls allowed in half-open state
     */
    public CircuitBreaker(int failureThreshold, int successThreshold, Duration timeout, int maxHalfOpenCalls) {
        this.failureThreshold = failureThreshold;
        this.successThreshold = successThreshold;
        this.timeout = timeout;
        this.maxHalfOpenCalls = maxHalfOpenCalls;
    }

    /**
     * Executes an operation with circuit breaker protection.
     * 
     * @param operation the operation to execute
     * @param fallback  the fallback operation to execute if circuit is open
     * @param <T>       the return type
     * @return the result of the operation or fallback
     * @throws Exception if the operation fails and no fallback is provided
     */
    public <T> T execute(CircuitBreakerOperation<T> operation, CircuitBreakerOperation<T> fallback) throws Exception {
        if (!canExecute()) {
            logger.debug("Circuit breaker is OPEN, executing fallback");
            if (fallback != null) {
                return fallback.execute();
            } else {
                throw new CircuitBreakerOpenException("Circuit breaker is open and no fallback provided");
            }
        }

        try {
            T result = operation.execute();
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure();
            throw e;
        }
    }

    /**
     * Executes an operation with circuit breaker protection without fallback.
     * 
     * @param operation the operation to execute
     * @param <T>       the return type
     * @return the result of the operation
     * @throws Exception if the operation fails or circuit is open
     */
    public <T> T execute(CircuitBreakerOperation<T> operation) throws Exception {
        return execute(operation, null);
    }

    /**
     * Checks if an operation can be executed based on circuit breaker state.
     * 
     * @return true if operation can be executed
     */
    public boolean canExecute() {
        State currentState = state.get();

        switch (currentState) {
            case CLOSED:
                return true;

            case OPEN:
                if (shouldAttemptReset()) {
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        halfOpenCalls.set(0);
                        logger.info("Circuit breaker transitioning from OPEN to HALF_OPEN");
                    }
                    // After transitioning to HALF_OPEN, check if we can execute
                    if (state.get() == State.HALF_OPEN) {
                        int currentCalls = halfOpenCalls.get();
                        if (currentCalls < maxHalfOpenCalls) {
                            halfOpenCalls.incrementAndGet();
                            return true;
                        }
                    }
                }
                return false;

            case HALF_OPEN:
                int currentCalls = halfOpenCalls.get();
                if (currentCalls < maxHalfOpenCalls) {
                    halfOpenCalls.incrementAndGet();
                    return true;
                }
                return false;

            default:
                return false;
        }
    }

    /**
     * Records a successful operation.
     */
    public void onSuccess() {
        State currentState = state.get();

        if (currentState == State.HALF_OPEN) {
            int successes = successCount.incrementAndGet();
            logger.debug("Circuit breaker success in HALF_OPEN state: {}/{}", successes, successThreshold);

            if (successes >= successThreshold) {
                reset();
                logger.info("Circuit breaker transitioning from HALF_OPEN to CLOSED after {} successes", successes);
            }
        } else if (currentState == State.CLOSED) {
            // Reset failure count on success in closed state
            failureCount.set(0);
        }
    }

    /**
     * Records a failed operation.
     */
    public void onFailure() {
        State currentState = state.get();
        lastFailureTime.set(System.currentTimeMillis());

        if (currentState == State.CLOSED) {
            int failures = failureCount.incrementAndGet();
            logger.debug("Circuit breaker failure in CLOSED state: {}/{}", failures, failureThreshold);

            if (failures >= failureThreshold) {
                if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                    logger.warn("Circuit breaker transitioning from CLOSED to OPEN after {} failures", failures);
                }
            }
        } else if (currentState == State.HALF_OPEN) {
            // Any failure in half-open state should open the circuit
            if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                successCount.set(0);
                logger.warn("Circuit breaker transitioning from HALF_OPEN to OPEN due to failure");
            }
        }
    }

    /**
     * Resets the circuit breaker to closed state.
     */
    public void reset() {
        state.set(State.CLOSED);
        failureCount.set(0);
        successCount.set(0);
        halfOpenCalls.set(0);
        logger.info("Circuit breaker reset to CLOSED state");
    }

    /**
     * Forces the circuit breaker to open state.
     */
    public void forceOpen() {
        state.set(State.OPEN);
        lastFailureTime.set(System.currentTimeMillis());
        logger.warn("Circuit breaker forced to OPEN state");
    }

    /**
     * Gets the current state of the circuit breaker.
     * 
     * @return the current state
     */
    public State getState() {
        return state.get();
    }

    /**
     * Gets the current failure count.
     * 
     * @return the failure count
     */
    public int getFailureCount() {
        return failureCount.get();
    }

    /**
     * Gets the current success count (relevant in half-open state).
     * 
     * @return the success count
     */
    public int getSuccessCount() {
        return successCount.get();
    }

    /**
     * Gets the failure threshold.
     * 
     * @return the failure threshold
     */
    public int getFailureThreshold() {
        return failureThreshold;
    }

    /**
     * Gets the success threshold.
     * 
     * @return the success threshold
     */
    public int getSuccessThreshold() {
        return successThreshold;
    }

    /**
     * Gets the timeout duration.
     * 
     * @return the timeout duration
     */
    public Duration getTimeout() {
        return timeout;
    }

    /**
     * Gets the maximum number of half-open calls.
     * 
     * @return the maximum half-open calls
     */
    public int getMaxHalfOpenCalls() {
        return maxHalfOpenCalls;
    }

    /**
     * Gets the time of the last failure.
     * 
     * @return the last failure time as Instant
     */
    public Instant getLastFailureTime() {
        long timestamp = lastFailureTime.get();
        return timestamp > 0 ? Instant.ofEpochMilli(timestamp) : null;
    }

    /**
     * Checks if enough time has passed to attempt resetting the circuit breaker.
     * 
     * @return true if reset should be attempted
     */
    private boolean shouldAttemptReset() {
        long lastFailure = lastFailureTime.get();
        if (lastFailure == 0) {
            return true;
        }

        long elapsed = System.currentTimeMillis() - lastFailure;
        return elapsed >= timeout.toMillis();
    }

    /**
     * Gets circuit breaker statistics.
     * 
     * @return statistics object
     */
    public CircuitBreakerStats getStats() {
        return new CircuitBreakerStats(
                state.get(),
                failureCount.get(),
                successCount.get(),
                halfOpenCalls.get(),
                getLastFailureTime());
    }

    /**
     * Functional interface for operations that can be executed with circuit breaker
     * protection.
     * 
     * @param <T> the return type of the operation
     */
    @FunctionalInterface
    public interface CircuitBreakerOperation<T> {
        T execute() throws Exception;
    }

    /**
     * Exception thrown when circuit breaker is open and no fallback is provided.
     */
    public static class CircuitBreakerOpenException extends RuntimeException {
        public CircuitBreakerOpenException(String message) {
            super(message);
        }
    }

    /**
     * Statistics class for circuit breaker state.
     */
    public static class CircuitBreakerStats {
        private final State state;
        private final int failureCount;
        private final int successCount;
        private final int halfOpenCalls;
        private final Instant lastFailureTime;

        public CircuitBreakerStats(State state, int failureCount, int successCount,
                int halfOpenCalls, Instant lastFailureTime) {
            this.state = state;
            this.failureCount = failureCount;
            this.successCount = successCount;
            this.halfOpenCalls = halfOpenCalls;
            this.lastFailureTime = lastFailureTime;
        }

        public State getState() {
            return state;
        }

        public int getFailureCount() {
            return failureCount;
        }

        public int getSuccessCount() {
            return successCount;
        }

        public int getHalfOpenCalls() {
            return halfOpenCalls;
        }

        public Instant getLastFailureTime() {
            return lastFailureTime;
        }

        @Override
        public String toString() {
            return "CircuitBreakerStats{" +
                    "state=" + state +
                    ", failureCount=" + failureCount +
                    ", successCount=" + successCount +
                    ", halfOpenCalls=" + halfOpenCalls +
                    ", lastFailureTime=" + lastFailureTime +
                    '}';
        }
    }
}