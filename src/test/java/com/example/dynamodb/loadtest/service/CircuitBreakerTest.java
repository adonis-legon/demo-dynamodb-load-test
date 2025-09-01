package com.example.dynamodb.loadtest.service;

import com.example.dynamodb.loadtest.service.CircuitBreaker.CircuitBreakerOpenException;
import com.example.dynamodb.loadtest.service.CircuitBreaker.CircuitBreakerStats;
import com.example.dynamodb.loadtest.service.CircuitBreaker.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class CircuitBreakerTest {

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        // Use small values for faster testing
        circuitBreaker = new CircuitBreaker(3, 2, Duration.ofMillis(100), 2);
    }

    @Test
    void testInitialState() {
        assertEquals(State.CLOSED, circuitBreaker.getState());
        assertEquals(0, circuitBreaker.getFailureCount());
        assertEquals(0, circuitBreaker.getSuccessCount());
        assertTrue(circuitBreaker.canExecute());
    }

    @Test
    void testSuccessfulExecution() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);

        CircuitBreaker.CircuitBreakerOperation<String> operation = () -> {
            callCount.incrementAndGet();
            return "success";
        };

        String result = circuitBreaker.execute(operation);

        assertEquals("success", result);
        assertEquals(1, callCount.get());
        assertEquals(State.CLOSED, circuitBreaker.getState());
        assertEquals(0, circuitBreaker.getFailureCount());
    }

    @Test
    void testFailureInClosedState() {
        AtomicInteger callCount = new AtomicInteger(0);

        CircuitBreaker.CircuitBreakerOperation<String> operation = () -> {
            callCount.incrementAndGet();
            throw new RuntimeException("Test failure");
        };

        // First failure
        assertThrows(RuntimeException.class, () -> circuitBreaker.execute(operation));
        assertEquals(State.CLOSED, circuitBreaker.getState());
        assertEquals(1, circuitBreaker.getFailureCount());
        assertTrue(circuitBreaker.canExecute());

        // Second failure
        assertThrows(RuntimeException.class, () -> circuitBreaker.execute(operation));
        assertEquals(State.CLOSED, circuitBreaker.getState());
        assertEquals(2, circuitBreaker.getFailureCount());
        assertTrue(circuitBreaker.canExecute());

        // Third failure should open the circuit
        assertThrows(RuntimeException.class, () -> circuitBreaker.execute(operation));
        assertEquals(State.OPEN, circuitBreaker.getState());
        assertEquals(3, circuitBreaker.getFailureCount());
        assertFalse(circuitBreaker.canExecute());

        assertEquals(3, callCount.get());
    }

    @Test
    void testCircuitOpenWithFallback() throws Exception {
        // Force circuit to open
        circuitBreaker.forceOpen();
        assertEquals(State.OPEN, circuitBreaker.getState());

        AtomicInteger mainCallCount = new AtomicInteger(0);
        AtomicInteger fallbackCallCount = new AtomicInteger(0);

        CircuitBreaker.CircuitBreakerOperation<String> mainOperation = () -> {
            mainCallCount.incrementAndGet();
            return "main";
        };

        CircuitBreaker.CircuitBreakerOperation<String> fallbackOperation = () -> {
            fallbackCallCount.incrementAndGet();
            return "fallback";
        };

        String result = circuitBreaker.execute(mainOperation, fallbackOperation);

        assertEquals("fallback", result);
        assertEquals(0, mainCallCount.get());
        assertEquals(1, fallbackCallCount.get());
    }

    @Test
    void testCircuitOpenWithoutFallback() {
        // Force circuit to open
        circuitBreaker.forceOpen();
        assertEquals(State.OPEN, circuitBreaker.getState());

        CircuitBreaker.CircuitBreakerOperation<String> operation = () -> "test";

        assertThrows(CircuitBreakerOpenException.class, () -> circuitBreaker.execute(operation));
    }

    @Test
    void testTransitionToHalfOpen() throws Exception {
        // Force circuit to open
        circuitBreaker.forceOpen();
        assertEquals(State.OPEN, circuitBreaker.getState());

        // Wait for timeout
        Thread.sleep(150); // Timeout is 100ms

        // Should transition to half-open on first call
        assertTrue(circuitBreaker.canExecute());
        assertEquals(State.HALF_OPEN, circuitBreaker.getState());
    }

    @Test
    void testHalfOpenToClosedTransition() throws Exception {
        // Force circuit to open
        circuitBreaker.forceOpen();
        Thread.sleep(150); // Wait for timeout

        AtomicInteger callCount = new AtomicInteger(0);
        CircuitBreaker.CircuitBreakerOperation<String> operation = () -> {
            callCount.incrementAndGet();
            return "success";
        };

        // First success in half-open
        circuitBreaker.execute(operation);
        assertEquals(State.HALF_OPEN, circuitBreaker.getState());
        assertEquals(1, circuitBreaker.getSuccessCount());

        // Second success should close the circuit (success threshold is 2)
        circuitBreaker.execute(operation);
        assertEquals(State.CLOSED, circuitBreaker.getState());
        assertEquals(0, circuitBreaker.getSuccessCount()); // Reset after closing
        assertEquals(0, circuitBreaker.getFailureCount()); // Reset after closing

        assertEquals(2, callCount.get());
    }

    @Test
    void testHalfOpenToOpenTransition() throws Exception {
        // Force circuit to open
        circuitBreaker.forceOpen();
        Thread.sleep(150); // Wait for timeout

        AtomicInteger callCount = new AtomicInteger(0);
        CircuitBreaker.CircuitBreakerOperation<String> operation = () -> {
            callCount.incrementAndGet();
            throw new RuntimeException("Failure in half-open");
        };

        // Failure in half-open should immediately open the circuit
        assertThrows(RuntimeException.class, () -> circuitBreaker.execute(operation));
        assertEquals(State.OPEN, circuitBreaker.getState());
        assertEquals(0, circuitBreaker.getSuccessCount()); // Reset after opening
        assertFalse(circuitBreaker.canExecute());

        assertEquals(1, callCount.get());
    }

    @Test
    void testMaxHalfOpenCalls() throws Exception {
        // Force circuit to open
        circuitBreaker.forceOpen();
        Thread.sleep(150); // Wait for timeout

        // Should allow up to maxHalfOpenCalls (2 in our test config)
        assertTrue(circuitBreaker.canExecute()); // First call - transitions to HALF_OPEN and increments counter
        assertTrue(circuitBreaker.canExecute()); // Second call - increments counter to 2
        assertFalse(circuitBreaker.canExecute()); // Third call should be rejected (counter is at max)

        assertEquals(State.HALF_OPEN, circuitBreaker.getState());
    }

    @Test
    void testReset() {
        // Cause some failures
        circuitBreaker.onFailure();
        circuitBreaker.onFailure();
        assertEquals(2, circuitBreaker.getFailureCount());

        // Reset should clear everything
        circuitBreaker.reset();
        assertEquals(State.CLOSED, circuitBreaker.getState());
        assertEquals(0, circuitBreaker.getFailureCount());
        assertEquals(0, circuitBreaker.getSuccessCount());
        assertTrue(circuitBreaker.canExecute());
    }

    @Test
    void testForceOpen() {
        assertEquals(State.CLOSED, circuitBreaker.getState());

        circuitBreaker.forceOpen();
        assertEquals(State.OPEN, circuitBreaker.getState());
        assertFalse(circuitBreaker.canExecute());
        assertNotNull(circuitBreaker.getLastFailureTime());
    }

    @Test
    void testSuccessInClosedStateResetsFailureCount() {
        // Cause some failures but not enough to open
        circuitBreaker.onFailure();
        circuitBreaker.onFailure();
        assertEquals(2, circuitBreaker.getFailureCount());
        assertEquals(State.CLOSED, circuitBreaker.getState());

        // Success should reset failure count
        circuitBreaker.onSuccess();
        assertEquals(0, circuitBreaker.getFailureCount());
        assertEquals(State.CLOSED, circuitBreaker.getState());
    }

    @Test
    void testGetters() {
        assertEquals(3, circuitBreaker.getFailureThreshold());
        assertEquals(2, circuitBreaker.getSuccessThreshold());
        assertEquals(Duration.ofMillis(100), circuitBreaker.getTimeout());
        assertEquals(2, circuitBreaker.getMaxHalfOpenCalls());
    }

    @Test
    void testDefaultConstructor() {
        CircuitBreaker defaultCircuitBreaker = new CircuitBreaker();

        assertEquals(10, defaultCircuitBreaker.getFailureThreshold()); // Updated to match new default
        assertEquals(2, defaultCircuitBreaker.getSuccessThreshold()); // Updated to match new default
        assertEquals(Duration.ofSeconds(30), defaultCircuitBreaker.getTimeout()); // Updated to match new default
        assertEquals(5, defaultCircuitBreaker.getMaxHalfOpenCalls()); // Updated to match new default
        assertEquals(State.CLOSED, defaultCircuitBreaker.getState());
    }

    @Test
    void testGetStats() {
        circuitBreaker.onFailure();
        circuitBreaker.onFailure();

        CircuitBreakerStats stats = circuitBreaker.getStats();

        assertEquals(State.CLOSED, stats.getState());
        assertEquals(2, stats.getFailureCount());
        assertEquals(0, stats.getSuccessCount());
        assertEquals(0, stats.getHalfOpenCalls());
        assertNotNull(stats.getLastFailureTime());
    }

    @Test
    void testStatsToString() {
        CircuitBreakerStats stats = circuitBreaker.getStats();
        String statsString = stats.toString();

        assertNotNull(statsString);
        assertTrue(statsString.contains("CircuitBreakerStats"));
        assertTrue(statsString.contains("state="));
        assertTrue(statsString.contains("failureCount="));
    }

    @Test
    void testLastFailureTime() {
        assertNull(circuitBreaker.getLastFailureTime());

        Instant beforeFailure = Instant.now();
        circuitBreaker.onFailure();
        Instant afterFailure = Instant.now();

        Instant lastFailureTime = circuitBreaker.getLastFailureTime();
        assertNotNull(lastFailureTime);
        assertTrue(lastFailureTime.isAfter(beforeFailure.minusSeconds(1)));
        assertTrue(lastFailureTime.isBefore(afterFailure.plusSeconds(1)));
    }

    @Test
    void testConcurrentAccess() throws Exception {
        // This test verifies thread safety by running multiple threads
        int threadCount = 10;
        int operationsPerThread = 100;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < operationsPerThread; j++) {
                    final int operationId = j; // Make a final copy for lambda
                    try {
                        CircuitBreaker.CircuitBreakerOperation<String> operation = () -> {
                            // Simulate some failures
                            if ((threadId + operationId) % 10 == 0) {
                                throw new RuntimeException("Simulated failure");
                            }
                            return "success";
                        };

                        circuitBreaker.execute(operation);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    }
                }
            });
        }

        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        // Verify that we had both successes and failures
        assertTrue(successCount.get() > 0);
        assertTrue(failureCount.get() > 0);

        // Verify circuit breaker is in a valid state
        assertNotNull(circuitBreaker.getState());
    }

    @Test
    void testExecuteWithoutFallback() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);

        CircuitBreaker.CircuitBreakerOperation<String> operation = () -> {
            callCount.incrementAndGet();
            return "success";
        };

        String result = circuitBreaker.execute(operation);

        assertEquals("success", result);
        assertEquals(1, callCount.get());
    }

    @Test
    void testCircuitBreakerOpenExceptionMessage() {
        CircuitBreakerOpenException exception = new CircuitBreakerOpenException("Test message");
        assertEquals("Test message", exception.getMessage());
    }
}