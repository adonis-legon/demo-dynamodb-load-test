package com.example.dynamodb.loadtest.service;

import com.example.dynamodb.loadtest.model.TestMetrics;
import com.example.dynamodb.loadtest.service.MetricsCollectionService.TestSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class MetricsCollectionServiceTest {

    private MetricsCollectionService metricsService;

    @BeforeEach
    void setUp() {
        metricsService = new MetricsCollectionService();
        metricsService.reset(); // Ensure clean state for each test
    }

    @Test
    void recordMetric_ValidMetric_Success() {
        // Arrange
        TestMetrics metric = new TestMetrics(Duration.ofMillis(100), 5, 0, 10);
        metric.addError(TestMetrics.ERROR_TYPE_CAPACITY_EXCEEDED);
        metric.addError(TestMetrics.ERROR_TYPE_DUPLICATE_KEY);

        // Act
        metricsService.recordMetric(metric);

        // Assert
        TestSummary summary = metricsService.generateSummary();
        assertEquals(7, summary.getTotalOperations());
        assertEquals(5, summary.getTotalSuccesses());
        assertEquals(2, summary.getTotalErrors());
        assertEquals(1, summary.getErrorTypeCounts().get(TestMetrics.ERROR_TYPE_CAPACITY_EXCEEDED));
        assertEquals(1, summary.getErrorTypeCounts().get(TestMetrics.ERROR_TYPE_DUPLICATE_KEY));
    }

    @Test
    void recordMetric_NullMetric_Ignored() {
        // Act
        metricsService.recordMetric(null);

        // Assert
        TestSummary summary = metricsService.generateSummary();
        assertEquals(0, summary.getTotalOperations());
    }

    @Test
    void recordMetric_InvalidMetric_Ignored() {
        // Arrange
        TestMetrics invalidMetric = new TestMetrics();
        invalidMetric.setSuccessCount(-1); // Invalid negative count

        // Act
        metricsService.recordMetric(invalidMetric);

        // Assert
        TestSummary summary = metricsService.generateSummary();
        assertEquals(0, summary.getTotalOperations());
    }

    @Test
    void recordSuccess_ValidData_Success() {
        // Act
        metricsService.recordSuccess(Duration.ofMillis(150), 5);

        // Assert
        TestSummary summary = metricsService.generateSummary();
        assertEquals(1, summary.getTotalOperations());
        assertEquals(1, summary.getTotalSuccesses());
        assertEquals(0, summary.getTotalErrors());
    }

    @Test
    void recordError_ValidData_Success() {
        // Act
        metricsService.recordError(TestMetrics.ERROR_TYPE_NETWORK, Duration.ofMillis(200), 3);

        // Assert
        TestSummary summary = metricsService.generateSummary();
        assertEquals(1, summary.getTotalOperations());
        assertEquals(0, summary.getTotalSuccesses());
        assertEquals(1, summary.getTotalErrors());
        assertEquals(1, summary.getErrorTypeCounts().get(TestMetrics.ERROR_TYPE_NETWORK));
    }

    @Test
    void recordCapacityExceededError_ValidData_Success() {
        // Act
        metricsService.recordCapacityExceededError(Duration.ofMillis(300), 8);

        // Assert
        TestSummary summary = metricsService.generateSummary();
        assertEquals(1, summary.getTotalErrors());
        assertEquals(1, summary.getErrorTypeCounts().get(TestMetrics.ERROR_TYPE_CAPACITY_EXCEEDED));
    }

    @Test
    void recordDuplicateKeyError_ValidData_Success() {
        // Act
        metricsService.recordDuplicateKeyError(Duration.ofMillis(50), 2);

        // Assert
        TestSummary summary = metricsService.generateSummary();
        assertEquals(1, summary.getTotalErrors());
        assertEquals(1, summary.getErrorTypeCounts().get(TestMetrics.ERROR_TYPE_DUPLICATE_KEY));
    }

    @Test
    void recordThrottlingError_ValidData_Success() {
        // Act
        metricsService.recordThrottlingError(Duration.ofMillis(400), 12);

        // Assert
        TestSummary summary = metricsService.generateSummary();
        assertEquals(1, summary.getTotalErrors());
        assertEquals(1, summary.getErrorTypeCounts().get(TestMetrics.ERROR_TYPE_THROTTLING));
    }

    @Test
    void recordNetworkError_ValidData_Success() {
        // Act
        metricsService.recordNetworkError(5);

        // Assert
        TestSummary summary = metricsService.generateSummary();
        assertEquals(1, summary.getTotalErrors());
        assertEquals(1, summary.getErrorTypeCounts().get(TestMetrics.ERROR_TYPE_NETWORK));
    }

    @Test
    void recordTimeoutError_ValidData_Success() {
        // Act
        metricsService.recordTimeoutError(7);

        // Assert
        TestSummary summary = metricsService.generateSummary();
        assertEquals(1, summary.getTotalErrors());
        assertEquals(1, summary.getErrorTypeCounts().get(TestMetrics.ERROR_TYPE_TIMEOUT));
    }

    @Test
    void startTest_UpdatesStartTime() {
        // Arrange
        Instant beforeStart = Instant.now();

        // Act
        metricsService.startTest();

        // Assert
        TestSummary summary = metricsService.generateSummary();
        assertTrue(summary.getTestStartTime().isAfter(beforeStart.minusSeconds(1)));
        assertNull(summary.getTestEndTime());
    }

    @Test
    void endTest_UpdatesEndTime() {
        // Arrange
        metricsService.startTest();

        // Act
        metricsService.endTest();

        // Assert
        TestSummary summary = metricsService.generateSummary();
        assertNotNull(summary.getTestEndTime());
        assertTrue(summary.getTestEndTime().isAfter(summary.getTestStartTime()));
    }

    @Test
    void generateSummary_MultipleMetrics_CorrectAggregation() {
        // Arrange
        metricsService.startTest();
        metricsService.recordSuccess(Duration.ofMillis(100), 1);
        metricsService.recordSuccess(Duration.ofMillis(200), 2);
        metricsService.recordCapacityExceededError(Duration.ofMillis(300), 3);
        metricsService.recordDuplicateKeyError(Duration.ofMillis(150), 2);

        // Add small delay to ensure test duration > 0
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        metricsService.endTest();

        // Act
        TestSummary summary = metricsService.generateSummary();

        // Assert
        assertEquals(4, summary.getTotalOperations());
        assertEquals(2, summary.getTotalSuccesses());
        assertEquals(2, summary.getTotalErrors());
        assertEquals(50.0, summary.getSuccessRate(), 0.01);
        assertEquals(50.0, summary.getErrorRate(), 0.01);
        assertEquals(1, summary.getErrorTypeCounts().get(TestMetrics.ERROR_TYPE_CAPACITY_EXCEEDED));
        assertEquals(1, summary.getErrorTypeCounts().get(TestMetrics.ERROR_TYPE_DUPLICATE_KEY));
        assertTrue(summary.getTestDuration().toMillis() >= 0);
    }

    @Test
    void getMetricsForConcurrencyLevel_ExistingLevel_ReturnsMetrics() {
        // Arrange
        metricsService.recordSuccess(Duration.ofMillis(100), 5);
        metricsService.recordError(TestMetrics.ERROR_TYPE_NETWORK, Duration.ofMillis(200), 5);

        // Act
        TestMetrics metrics = metricsService.getMetricsForConcurrencyLevel(5);

        // Assert
        assertNotNull(metrics);
        assertEquals(5, metrics.getConcurrencyLevel());
        assertEquals(2, metrics.getTotalOperations());
    }

    @Test
    void getMetricsForConcurrencyLevel_NonExistingLevel_ReturnsNull() {
        // Act
        TestMetrics metrics = metricsService.getMetricsForConcurrencyLevel(999);

        // Assert
        assertNull(metrics);
    }

    @Test
    void getAllMetrics_ReturnsAllRecordedMetrics() {
        // Arrange
        metricsService.recordSuccess(Duration.ofMillis(100), 1);
        metricsService.recordError(TestMetrics.ERROR_TYPE_TIMEOUT, Duration.ofMillis(200), 2);

        // Act
        List<TestMetrics> allMetrics = metricsService.getAllMetrics();

        // Assert
        assertEquals(2, allMetrics.size());
    }

    @Test
    void reset_ClearsAllMetrics() {
        // Arrange
        metricsService.recordSuccess(Duration.ofMillis(100), 1);
        metricsService.recordError(TestMetrics.ERROR_TYPE_NETWORK, Duration.ofMillis(200), 2);

        // Act
        metricsService.reset();

        // Assert
        TestSummary summary = metricsService.generateSummary();
        assertEquals(0, summary.getTotalOperations());
        assertEquals(0, summary.getTotalSuccesses());
        assertEquals(0, summary.getTotalErrors());
        assertTrue(summary.getErrorTypeCounts().isEmpty());
        assertTrue(metricsService.getAllMetrics().isEmpty());
    }

    @Test
    void getCurrentErrorRate_CalculatesCorrectly() {
        // Arrange
        metricsService.recordSuccess(Duration.ofMillis(100), 1);
        metricsService.recordError(TestMetrics.ERROR_TYPE_NETWORK, Duration.ofMillis(200), 1);
        metricsService.recordError(TestMetrics.ERROR_TYPE_TIMEOUT, Duration.ofMillis(300), 1);

        // Act
        double errorRate = metricsService.getCurrentErrorRate();

        // Assert
        assertEquals(66.67, errorRate, 0.01); // 2 errors out of 3 total operations
    }

    @Test
    void getCurrentSuccessRate_CalculatesCorrectly() {
        // Arrange
        metricsService.recordSuccess(Duration.ofMillis(100), 1);
        metricsService.recordSuccess(Duration.ofMillis(150), 1);
        metricsService.recordError(TestMetrics.ERROR_TYPE_NETWORK, Duration.ofMillis(200), 1);

        // Act
        double successRate = metricsService.getCurrentSuccessRate();

        // Assert
        assertEquals(66.67, successRate, 0.01); // 2 successes out of 3 total operations
    }

    @Test
    void getErrorTypeCount_ReturnsCorrectCount() {
        // Arrange
        metricsService.recordCapacityExceededError(Duration.ofMillis(100), 1);
        metricsService.recordCapacityExceededError(Duration.ofMillis(200), 2);
        metricsService.recordDuplicateKeyError(Duration.ofMillis(150), 1);

        // Act
        long capacityErrors = metricsService.getErrorTypeCount(TestMetrics.ERROR_TYPE_CAPACITY_EXCEEDED);
        long duplicateErrors = metricsService.getErrorTypeCount(TestMetrics.ERROR_TYPE_DUPLICATE_KEY);
        long networkErrors = metricsService.getErrorTypeCount(TestMetrics.ERROR_TYPE_NETWORK);

        // Assert
        assertEquals(2, capacityErrors);
        assertEquals(1, duplicateErrors);
        assertEquals(0, networkErrors);
    }

    @Test
    void concurrentAccess_ThreadSafe() throws InterruptedException {
        // Arrange
        int threadCount = 10;
        int operationsPerThread = 100;
        Thread[] threads = new Thread[threadCount];

        // Act
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < operationsPerThread; j++) {
                    if (j % 2 == 0) {
                        metricsService.recordSuccess(Duration.ofMillis(100 + j), threadId + 1);
                    } else {
                        metricsService.recordError(TestMetrics.ERROR_TYPE_NETWORK,
                                Duration.ofMillis(200 + j), threadId + 1);
                    }
                }
            });
            threads[i].start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        // Assert
        TestSummary summary = metricsService.generateSummary();
        assertEquals(threadCount * operationsPerThread, summary.getTotalOperations());
        assertEquals(threadCount * operationsPerThread / 2, summary.getTotalSuccesses());
        assertEquals(threadCount * operationsPerThread / 2, summary.getTotalErrors());
    }

    @Test
    void testSummary_CalculatesPercentiles() {
        // Arrange
        metricsService.recordSuccess(Duration.ofMillis(100), 1);
        metricsService.recordSuccess(Duration.ofMillis(200), 1);
        metricsService.recordSuccess(Duration.ofMillis(300), 1);
        metricsService.recordSuccess(Duration.ofMillis(400), 1);
        metricsService.recordSuccess(Duration.ofMillis(500), 1);

        // Act
        TestSummary summary = metricsService.generateSummary();

        // Assert
        Map<String, Duration> percentiles = summary.getResponseTimePercentiles();
        assertNotNull(percentiles.get("p50"));
        assertNotNull(percentiles.get("p90"));
        assertNotNull(percentiles.get("p95"));
        assertNotNull(percentiles.get("p99"));
        assertTrue(percentiles.get("p50").toMillis() >= 100);
        assertTrue(percentiles.get("p99").toMillis() <= 500);
    }

    @Test
    void testSummary_CalculatesThroughput() {
        // Arrange
        metricsService.startTest();
        metricsService.recordSuccess(Duration.ofMillis(100), 1);
        metricsService.recordSuccess(Duration.ofMillis(200), 1);

        // Wait a small amount to ensure test duration > 0
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        metricsService.endTest();

        // Act
        TestSummary summary = metricsService.generateSummary();

        // Assert
        assertTrue(summary.getThroughputPerSecond() > 0);
        assertTrue(summary.getTestDuration().toMillis() > 0);
    }
}