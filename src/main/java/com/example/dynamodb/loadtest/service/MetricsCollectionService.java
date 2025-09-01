package com.example.dynamodb.loadtest.service;

import com.example.dynamodb.loadtest.model.TestMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Service for collecting and aggregating test metrics during load testing.
 * Provides thread-safe operations for recording metrics and generating
 * summaries.
 */
@Service
public class MetricsCollectionService {

    private static final Logger logger = LoggerFactory.getLogger(MetricsCollectionService.class);

    private final ConcurrentLinkedQueue<TestMetrics> metricsQueue;
    private final Map<Integer, TestMetrics> concurrencyLevelMetrics;
    private final AtomicLong totalOperations;
    private final AtomicLong totalSuccesses;
    private final AtomicLong totalErrors;
    private final Map<String, AtomicLong> errorTypeCounts;
    private final ReadWriteLock lock;
    private volatile Instant testStartTime;
    private volatile Instant testEndTime;

    public MetricsCollectionService() {
        this.metricsQueue = new ConcurrentLinkedQueue<>();
        this.concurrencyLevelMetrics = new ConcurrentHashMap<>();
        this.totalOperations = new AtomicLong(0);
        this.totalSuccesses = new AtomicLong(0);
        this.totalErrors = new AtomicLong(0);
        this.errorTypeCounts = new ConcurrentHashMap<>();
        this.lock = new ReentrantReadWriteLock();
        this.testStartTime = Instant.now();

        logger.info("Metrics collection service initialized");
    }

    /**
     * Records a single metric entry.
     * 
     * @param metric the metric to record
     */
    public void recordMetric(TestMetrics metric) {
        if (metric == null || !metric.isValid()) {
            logger.warn("Invalid metric provided, skipping recording");
            return;
        }

        lock.readLock().lock();
        try {
            // Add to queue for detailed tracking
            metricsQueue.offer(metric.createSnapshot());

            // Update aggregate counters
            totalOperations.addAndGet(metric.getTotalOperations());
            totalSuccesses.addAndGet(metric.getSuccessCount());
            totalErrors.addAndGet(metric.getErrorCount());

            // Update error type counts
            for (Map.Entry<String, Integer> errorEntry : metric.getErrorTypes().entrySet()) {
                errorTypeCounts.computeIfAbsent(errorEntry.getKey(), k -> new AtomicLong(0))
                        .addAndGet(errorEntry.getValue());
            }

            // Update concurrency level metrics
            concurrencyLevelMetrics.merge(metric.getConcurrencyLevel(), metric.createSnapshot(),
                    (existing, incoming) -> {
                        existing.merge(incoming);
                        return existing;
                    });

            logger.debug("Recorded metric: concurrency={}, success={}, errors={}, responseTime={}ms",
                    metric.getConcurrencyLevel(), metric.getSuccessCount(), metric.getErrorCount(),
                    metric.getResponseTime().toMillis());

        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Records response time for a successful operation.
     * 
     * @param responseTime     the response time
     * @param concurrencyLevel the concurrency level when the operation occurred
     */
    public void recordSuccess(Duration responseTime, int concurrencyLevel) {
        TestMetrics metric = new TestMetrics(responseTime, 1, 0, concurrencyLevel);
        recordMetric(metric);
    }

    /**
     * Records an error with categorization.
     * 
     * @param errorType        the type of error
     * @param responseTime     the response time (may be null for timeouts)
     * @param concurrencyLevel the concurrency level when the error occurred
     */
    public void recordError(String errorType, Duration responseTime, int concurrencyLevel) {
        TestMetrics metric = new TestMetrics(
                responseTime != null ? responseTime : Duration.ZERO,
                0, 0, concurrencyLevel);
        metric.addError(errorType);
        recordMetric(metric);
    }

    /**
     * Records a capacity exceeded error.
     * 
     * @param responseTime     the response time
     * @param concurrencyLevel the concurrency level
     */
    public void recordCapacityExceededError(Duration responseTime, int concurrencyLevel) {
        recordError(TestMetrics.ERROR_TYPE_CAPACITY_EXCEEDED, responseTime, concurrencyLevel);
    }

    /**
     * Records a duplicate key error.
     * 
     * @param responseTime     the response time
     * @param concurrencyLevel the concurrency level
     */
    public void recordDuplicateKeyError(Duration responseTime, int concurrencyLevel) {
        recordError(TestMetrics.ERROR_TYPE_DUPLICATE_KEY, responseTime, concurrencyLevel);
    }

    /**
     * Records a throttling error.
     * 
     * @param responseTime     the response time
     * @param concurrencyLevel the concurrency level
     */
    public void recordThrottlingError(Duration responseTime, int concurrencyLevel) {
        recordError(TestMetrics.ERROR_TYPE_THROTTLING, responseTime, concurrencyLevel);
    }

    /**
     * Records a network error.
     * 
     * @param concurrencyLevel the concurrency level
     */
    public void recordNetworkError(int concurrencyLevel) {
        recordError(TestMetrics.ERROR_TYPE_NETWORK, null, concurrencyLevel);
    }

    /**
     * Records a timeout error.
     * 
     * @param concurrencyLevel the concurrency level
     */
    public void recordTimeoutError(int concurrencyLevel) {
        recordError(TestMetrics.ERROR_TYPE_TIMEOUT, null, concurrencyLevel);
    }

    /**
     * Marks the start of the test.
     */
    public void startTest() {
        lock.writeLock().lock();
        try {
            this.testStartTime = Instant.now();
            this.testEndTime = null;
            logger.info("Test started at {}", testStartTime);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Marks the end of the test.
     */
    public void endTest() {
        lock.writeLock().lock();
        try {
            this.testEndTime = Instant.now();
            logger.info("Test ended at {}, duration: {}ms",
                    testEndTime, Duration.between(testStartTime, testEndTime).toMillis());

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Generates a comprehensive test summary.
     * 
     * @return TestSummary containing aggregated metrics
     */
    public TestSummary generateSummary() {
        lock.readLock().lock();
        try {
            Duration testDuration = testEndTime != null
                    ? Duration.between(testStartTime, testEndTime)
                    : Duration.between(testStartTime, Instant.now());

            return new TestSummary(
                    totalOperations.get(),
                    totalSuccesses.get(),
                    totalErrors.get(),
                    new HashMap<>(convertAtomicMapToRegular(errorTypeCounts)),
                    testDuration,
                    testStartTime,
                    testEndTime,
                    new HashMap<>(concurrencyLevelMetrics),
                    calculatePercentiles(),
                    calculateAverageResponseTime(),
                    calculateThroughput(testDuration));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets metrics for a specific concurrency level.
     * 
     * @param concurrencyLevel the concurrency level
     * @return metrics for the specified concurrency level, or null if not found
     */
    public TestMetrics getMetricsForConcurrencyLevel(int concurrencyLevel) {
        TestMetrics metrics = concurrencyLevelMetrics.get(concurrencyLevel);
        return metrics != null ? metrics.createSnapshot() : null;
    }

    /**
     * Gets all recorded metrics as a list.
     * 
     * @return list of all recorded metrics
     */
    public List<TestMetrics> getAllMetrics() {
        return new ArrayList<>(metricsQueue);
    }

    /**
     * Clears all collected metrics and resets counters.
     */
    public void reset() {
        lock.writeLock().lock();
        try {
            metricsQueue.clear();
            concurrencyLevelMetrics.clear();
            totalOperations.set(0);
            totalSuccesses.set(0);
            totalErrors.set(0);
            errorTypeCounts.clear();
            testStartTime = Instant.now();
            testEndTime = null;
            logger.info("Metrics collection service reset");
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Gets the current error rate as a percentage.
     * 
     * @return error rate (0.0 to 100.0)
     */
    public double getCurrentErrorRate() {
        long total = totalOperations.get();
        return total > 0 ? (totalErrors.get() * 100.0) / total : 0.0;
    }

    /**
     * Gets the current success rate as a percentage.
     * 
     * @return success rate (0.0 to 100.0)
     */
    public double getCurrentSuccessRate() {
        long total = totalOperations.get();
        return total > 0 ? (totalSuccesses.get() * 100.0) / total : 0.0;
    }

    /**
     * Gets the count of a specific error type.
     * 
     * @param errorType the error type
     * @return count of the specified error type
     */
    public long getErrorTypeCount(String errorType) {
        AtomicLong count = errorTypeCounts.get(errorType);
        return count != null ? count.get() : 0;
    }

    // Private helper methods

    private Map<String, Long> convertAtomicMapToRegular(Map<String, AtomicLong> atomicMap) {
        Map<String, Long> regularMap = new HashMap<>();
        for (Map.Entry<String, AtomicLong> entry : atomicMap.entrySet()) {
            regularMap.put(entry.getKey(), entry.getValue().get());
        }
        return regularMap;
    }

    private Map<String, Duration> calculatePercentiles() {
        List<Duration> responseTimes = metricsQueue.stream()
                .map(TestMetrics::getResponseTime)
                .filter(duration -> !duration.isZero())
                .sorted()
                .toList();

        Map<String, Duration> percentiles = new HashMap<>();
        if (!responseTimes.isEmpty()) {
            percentiles.put("p50", getPercentile(responseTimes, 50));
            percentiles.put("p90", getPercentile(responseTimes, 90));
            percentiles.put("p95", getPercentile(responseTimes, 95));
            percentiles.put("p99", getPercentile(responseTimes, 99));
        }
        return percentiles;
    }

    private Duration getPercentile(List<Duration> sortedDurations, int percentile) {
        if (sortedDurations.isEmpty()) {
            return Duration.ZERO;
        }
        int index = (int) Math.ceil((percentile / 100.0) * sortedDurations.size()) - 1;
        index = Math.max(0, Math.min(index, sortedDurations.size() - 1));
        return sortedDurations.get(index);
    }

    private Duration calculateAverageResponseTime() {
        List<TestMetrics> metrics = new ArrayList<>(metricsQueue);
        if (metrics.isEmpty()) {
            return Duration.ZERO;
        }

        long totalMillis = metrics.stream()
                .mapToLong(m -> m.getResponseTime().toMillis())
                .sum();

        return Duration.ofMillis(totalMillis / metrics.size());
    }

    private double calculateThroughput(Duration testDuration) {
        if (testDuration.isZero() || testDuration.isNegative()) {
            return 0.0;
        }
        double seconds = testDuration.toMillis() / 1000.0;
        return totalOperations.get() / seconds;
    }

    /**
     * Inner class representing a comprehensive test summary.
     */
    public static class TestSummary {
        private final long totalOperations;
        private final long totalSuccesses;
        private final long totalErrors;
        private final Map<String, Long> errorTypeCounts;
        private final Duration testDuration;
        private final Instant testStartTime;
        private final Instant testEndTime;
        private final Map<Integer, TestMetrics> concurrencyLevelMetrics;
        private final Map<String, Duration> responseTimePercentiles;
        private final Duration averageResponseTime;
        private final double throughputPerSecond;

        public TestSummary(long totalOperations, long totalSuccesses, long totalErrors,
                Map<String, Long> errorTypeCounts, Duration testDuration,
                Instant testStartTime, Instant testEndTime,
                Map<Integer, TestMetrics> concurrencyLevelMetrics,
                Map<String, Duration> responseTimePercentiles,
                Duration averageResponseTime, double throughputPerSecond) {
            this.totalOperations = totalOperations;
            this.totalSuccesses = totalSuccesses;
            this.totalErrors = totalErrors;
            this.errorTypeCounts = new HashMap<>(errorTypeCounts);
            this.testDuration = testDuration;
            this.testStartTime = testStartTime;
            this.testEndTime = testEndTime;
            this.concurrencyLevelMetrics = new HashMap<>(concurrencyLevelMetrics);
            this.responseTimePercentiles = new HashMap<>(responseTimePercentiles);
            this.averageResponseTime = averageResponseTime;
            this.throughputPerSecond = throughputPerSecond;
        }

        // Getters
        public long getTotalOperations() {
            return totalOperations;
        }

        public long getTotalSuccesses() {
            return totalSuccesses;
        }

        public long getTotalErrors() {
            return totalErrors;
        }

        public Map<String, Long> getErrorTypeCounts() {
            return new HashMap<>(errorTypeCounts);
        }

        public Duration getTestDuration() {
            return testDuration;
        }

        public Instant getTestStartTime() {
            return testStartTime;
        }

        public Instant getTestEndTime() {
            return testEndTime;
        }

        public Map<Integer, TestMetrics> getConcurrencyLevelMetrics() {
            return new HashMap<>(concurrencyLevelMetrics);
        }

        public Map<String, Duration> getResponseTimePercentiles() {
            return new HashMap<>(responseTimePercentiles);
        }

        public Duration getAverageResponseTime() {
            return averageResponseTime;
        }

        public double getThroughputPerSecond() {
            return throughputPerSecond;
        }

        public double getSuccessRate() {
            return totalOperations > 0 ? (totalSuccesses * 100.0) / totalOperations : 0.0;
        }

        public double getErrorRate() {
            return totalOperations > 0 ? (totalErrors * 100.0) / totalOperations : 0.0;
        }

        @Override
        public String toString() {
            return "TestSummary{" +
                    "totalOperations=" + totalOperations +
                    ", totalSuccesses=" + totalSuccesses +
                    ", totalErrors=" + totalErrors +
                    ", successRate=" + String.format("%.2f%%", getSuccessRate()) +
                    ", errorRate=" + String.format("%.2f%%", getErrorRate()) +
                    ", testDuration=" + testDuration +
                    ", averageResponseTime=" + averageResponseTime +
                    ", throughputPerSecond=" + String.format("%.2f", throughputPerSecond) +
                    ", errorTypeCounts=" + errorTypeCounts +
                    ", responseTimePercentiles=" + responseTimePercentiles +
                    '}';
        }
    }
}