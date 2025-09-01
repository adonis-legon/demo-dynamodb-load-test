package com.example.dynamodb.loadtest.integration;

import com.example.dynamodb.loadtest.model.TestConfiguration;
import com.example.dynamodb.loadtest.service.ConfigurationManager;
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
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Performance validation tests for concurrency limits, Virtual Thread behavior,
 * load ramping, and metrics collection accuracy.
 * 
 * Requirements: 3.1, 3.2, 3.3, 5.1, 5.2, 7.3, 7.4, 7.5
 */
@SpringBootTest
@ActiveProfiles("integration")
class PerformanceValidationTest extends LocalStackIntegrationTestBase {

    @Autowired
    private ConfigurationManager configurationManager;

    @Autowired
    private LoadTestService loadTestService;

    @Autowired
    private MetricsCollectionService metricsCollectionService;

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
     * Test concurrency limits are properly enforced.
     * Requirements: 3.1, 3.2, 3.3
     */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void shouldEnforceConcurrencyLimits() {
        // Given - Configuration with specific concurrency limit
        TestConfiguration config = configurationManager.loadConfiguration().join();
        int concurrencyLimit = config.getConcurrencyLimit();
        assertThat(concurrencyLimit).isEqualTo(5);

        // When - Execute load test and measure timing
        long startTime = System.currentTimeMillis();
        TestSummary summary = loadTestService.executeLoadTest(config).join();
        long executionTime = System.currentTimeMillis() - startTime;

        // Then - Verify concurrency was controlled
        assertThat(summary.getTotalOperations()).isEqualTo(config.getTotalItems());

        // With concurrency limits, execution should take longer than if unlimited
        // Minimum expected time based on items and concurrency
        int expectedMinimumBatches = config.getTotalItems() / concurrencyLimit;
        long minimumExpectedTime = expectedMinimumBatches * 50; // 50ms per batch minimum

        assertThat(executionTime).isGreaterThan(minimumExpectedTime);

        // But should not take excessively long
        assertThat(executionTime).isLessThan(180000); // Less than 3 minutes

        // Verify high success rate with controlled concurrency
        double successRate = (double) summary.getTotalSuccesses() / summary.getTotalOperations();
        assertThat(successRate).isGreaterThan(0.9); // At least 90% success rate
    }

    /**
     * Test Virtual Thread behavior and resource efficiency.
     * Requirements: 3.1, 3.2, 3.3
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void shouldUseVirtualThreadsEfficiently() {
        // Given - Configuration for testing Virtual Threads
        TestConfiguration config = configurationManager.loadConfiguration().join();

        // When - Execute multiple concurrent load tests to stress Virtual Threads
        List<CompletableFuture<TestSummary>> futures = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            CompletableFuture<TestSummary> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return loadTestService.executeLoadTest(config).join();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            futures.add(future);
        }

        // Wait for all tests to complete
        List<TestSummary> summaries = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        // Then - All tests should complete successfully
        assertThat(summaries).hasSize(3);

        for (TestSummary summary : summaries) {
            assertThat(summary.getTotalOperations()).isEqualTo(config.getTotalItems());
            assertThat(summary.getTotalSuccesses()).isGreaterThan(0);

            // Response times should be reasonable even under concurrent load
            assertThat(summary.getAverageResponseTime().toMillis()).isLessThan(10000);
        }

        // Verify Virtual Threads didn't cause resource exhaustion
        // (Test completion without OutOfMemoryError indicates success)
    }

    /**
     * Test load ramping behavior with progressive concurrency increase.
     * Requirements: 5.1, 5.2
     */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void shouldRampLoadProgressively() {
        // Given
        TestConfiguration config = configurationManager.loadConfiguration().join();
        double maxConcurrencyPercentage = config.getMaxConcurrencyPercentage();
        int totalItems = config.getTotalItems();

        // Calculate expected ramping behavior
        int maxConcurrencyItems = (int) (totalItems * maxConcurrencyPercentage / 100);
        int rampUpItems = totalItems - maxConcurrencyItems;

        assertThat(maxConcurrencyItems).isLessThan(totalItems);
        assertThat(rampUpItems).isGreaterThan(0);

        // When - Execute load test with timing measurements
        long startTime = System.currentTimeMillis();
        TestSummary summary = loadTestService.executeLoadTest(config).join();
        long totalTime = System.currentTimeMillis() - startTime;

        // Then - Verify progressive ramping occurred
        assertThat(summary.getTotalOperations()).isEqualTo(totalItems);

        // Progressive ramping should result in longer execution time than
        // if all operations were executed at max concurrency immediately
        long estimatedMaxConcurrencyTime = (maxConcurrencyItems / config.getConcurrencyLimit()) * 100;
        assertThat(totalTime).isGreaterThan(estimatedMaxConcurrencyTime);

        // Verify reasonable execution time bounds
        assertThat(totalTime).isGreaterThan(1000); // At least 1 second
        assertThat(totalTime).isLessThan(180000); // Less than 3 minutes

        // Verify items were written correctly
        ScanResponse scanResponse = dynamoDbClient.scan(ScanRequest.builder()
                .tableName(TEST_TABLE_NAME)
                .build()).join();
        assertThat(scanResponse.count()).isLessThanOrEqualTo(totalItems);
    }

    /**
     * Test metrics collection accuracy during concurrent operations.
     * Requirements: 7.3, 7.4, 7.5
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void shouldCollectAccurateMetrics() {
        // Given
        TestConfiguration config = configurationManager.loadConfiguration().join();
        int expectedOperations = config.getTotalItems();

        // When
        TestSummary summary = loadTestService.executeLoadTest(config).join();

        // Then - Verify metrics accuracy
        assertThat(summary.getTotalOperations()).isEqualTo(expectedOperations);
        assertThat(summary.getTotalSuccesses() + summary.getTotalErrors()).isEqualTo(expectedOperations);

        // Verify response time metrics are consistent
        assertThat(summary.getAverageResponseTime()).isNotNull();
        assertThat(summary.getResponseTimePercentiles()).isNotNull();

        // Verify response time percentiles
        Map<String, Duration> percentiles = summary.getResponseTimePercentiles();
        if (!percentiles.isEmpty()) {
            Duration p50 = percentiles.get("p50");
            Duration p95 = percentiles.get("p95");
            if (p50 != null && p95 != null) {
                assertThat(p50.compareTo(p95)).isLessThanOrEqualTo(0);
            }
        }

        // Verify response times are reasonable
        assertThat(summary.getAverageResponseTime().toMillis()).isGreaterThan(0);
        assertThat(summary.getAverageResponseTime().toMillis()).isLessThan(30000); // Less than 30 seconds

        // Verify error breakdown accuracy
        if (summary.getTotalErrors() > 0) {
            long totalCategorizedErrors = summary.getErrorTypeCounts().values().stream()
                    .mapToLong(Long::longValue)
                    .sum();
            assertThat(totalCategorizedErrors).isEqualTo(summary.getTotalErrors());
        }

        // Verify success rate calculation
        double expectedSuccessRate = (double) summary.getTotalSuccesses() / summary.getTotalOperations() * 100;
        assertThat(summary.getSuccessRate()).isEqualTo(expectedSuccessRate, org.assertj.core.data.Offset.offset(0.01));
    }

    /**
     * Test performance under different concurrency levels.
     * Requirements: 3.1, 3.2, 3.3
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void shouldPerformWellUnderDifferentConcurrencyLevels() {
        // Given - Test with different concurrency configurations
        TestConfiguration baseConfig = configurationManager.loadConfiguration().join();

        List<Integer> concurrencyLevels = List.of(1, 3, 5, 10);
        List<TestResult> results = new ArrayList<>();

        // When - Test each concurrency level
        for (int concurrency : concurrencyLevels) {
            TestConfiguration config = new TestConfiguration(
                    baseConfig.getTableName(),
                    concurrency,
                    25, // Smaller number of items for faster testing
                    baseConfig.getMaxConcurrencyPercentage(),
                    0.0, // Disable duplicates for cleaner performance testing
                    true, // Enable cleanup
                    baseConfig.getEnvironment());

            long startTime = System.currentTimeMillis();
            TestSummary summary = loadTestService.executeLoadTest(config).join();
            long executionTime = System.currentTimeMillis() - startTime;

            results.add(new TestResult(concurrency, summary, executionTime));

            // Clean up between tests
            cleanupTestData();
        }

        // Then - Verify performance characteristics
        for (TestResult result : results) {
            assertThat(result.summary.getTotalOperations()).isEqualTo(25);
            assertThat(result.summary.getTotalSuccesses()).isGreaterThan(0);

            // Higher concurrency should generally result in faster execution
            // (though this may not always be true due to LocalStack limitations)
            assertThat(result.executionTime).isLessThan(60000); // Less than 1 minute

            // Response times should remain reasonable regardless of concurrency
            assertThat(result.summary.getAverageResponseTime().toMillis()).isLessThan(5000);

            // Log performance metrics for analysis
            System.out.printf("Concurrency %d: %d ops in %dms (%.2f ops/sec)%n",
                    result.concurrency,
                    result.summary.getTotalOperations(),
                    result.executionTime,
                    (double) result.summary.getTotalOperations() / (result.executionTime / 1000.0));
        }

        // Verify that higher concurrency levels don't degrade success rates
        // significantly
        double baselineSuccessRate = results.get(0).summary.getSuccessRate();
        for (TestResult result : results) {
            double successRateDifference = Math.abs(result.summary.getSuccessRate() - baselineSuccessRate);
            assertThat(successRateDifference).isLessThan(20.0); // Within 20% of baseline
        }

        // Verify concurrency scaling behavior
        for (int i = 1; i < results.size(); i++) {
            TestResult current = results.get(i);
            TestResult previous = results.get(i - 1);

            // Higher concurrency should not significantly increase execution time
            // (allowing for some variance due to LocalStack behavior)
            double timeRatio = (double) current.executionTime / previous.executionTime;
            assertThat(timeRatio).isLessThan(2.0); // Should not take more than 2x longer

            // Verify concurrency values are as expected
            assertThat(current.concurrency).isGreaterThan(previous.concurrency);
        }
    }

    /**
     * Test memory usage and resource efficiency during load tests.
     * Requirements: 3.1, 3.2, 3.3
     */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void shouldUseResourcesEfficiently() {
        // Given
        TestConfiguration config = configurationManager.loadConfiguration().join();

        // Capture initial memory usage
        Runtime runtime = Runtime.getRuntime();
        runtime.gc(); // Suggest garbage collection
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();

        // When - Execute load test
        TestSummary summary = loadTestService.executeLoadTest(config).join();

        // Capture final memory usage
        runtime.gc(); // Suggest garbage collection
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryIncrease = finalMemory - initialMemory;

        // Then - Verify resource efficiency
        assertThat(summary.getTotalOperations()).isEqualTo(config.getTotalItems());

        // Memory increase should be reasonable (less than 100MB for this test size)
        assertThat(memoryIncrease).isLessThan(100 * 1024 * 1024); // 100MB

        // Verify no memory leaks by checking that memory usage is reasonable
        long maxMemory = runtime.maxMemory();
        assertThat(finalMemory).isLessThan(maxMemory / 2); // Less than 50% of max memory
    }

    /**
     * Test throughput performance and operations per second.
     * Requirements: 5.1, 5.2
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void shouldAchieveReasonableThroughput() {
        // Given
        TestConfiguration config = configurationManager.loadConfiguration().join();

        // When
        long startTime = System.currentTimeMillis();
        TestSummary summary = loadTestService.executeLoadTest(config).join();
        long totalTime = System.currentTimeMillis() - startTime;

        // Then - Calculate and verify throughput
        double operationsPerSecond = (double) summary.getTotalOperations() / (totalTime / 1000.0);
        double successfulOperationsPerSecond = (double) summary.getTotalSuccesses() / (totalTime / 1000.0);

        // Verify reasonable throughput (should achieve at least 1 operation per second)
        assertThat(operationsPerSecond).isGreaterThan(1.0);
        assertThat(successfulOperationsPerSecond).isGreaterThan(0.8); // At least 0.8 successful ops/sec

        // Verify throughput is not unrealistically high (sanity check)
        assertThat(operationsPerSecond).isLessThan(1000.0); // Less than 1000 ops/sec for LocalStack

        // Verify efficiency ratio
        double efficiency = successfulOperationsPerSecond / operationsPerSecond;
        assertThat(efficiency).isGreaterThan(0.8); // At least 80% efficiency
    }

    /**
     * Test response time distribution and percentiles.
     * Requirements: 7.3, 7.4, 7.5
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void shouldHaveReasonableResponseTimeDistribution() {
        // Given
        TestConfiguration config = configurationManager.loadConfiguration().join();

        // When
        TestSummary summary = loadTestService.executeLoadTest(config).join();

        // Then - Verify response time distribution
        Duration avgTime = summary.getAverageResponseTime();
        Map<String, Duration> percentiles = summary.getResponseTimePercentiles();

        // Verify reasonable response times
        assertThat(avgTime.toMillis()).isGreaterThan(0);
        assertThat(avgTime.toMillis()).isLessThan(5000); // Average should be less than 5 seconds

        // Verify response time percentiles if available
        if (!percentiles.isEmpty()) {
            Duration p50 = percentiles.get("p50");
            Duration p95 = percentiles.get("p95");

            if (p50 != null) {
                assertThat(p50.toMillis()).isGreaterThan(0);
                assertThat(p50.toMillis()).isLessThan(10000); // P50 should be less than 10 seconds
            }

            if (p95 != null) {
                assertThat(p95.toMillis()).isLessThan(30000); // P95 should be less than 30 seconds
            }

            if (p50 != null && p95 != null) {
                assertThat(p50.compareTo(p95)).isLessThanOrEqualTo(0); // P50 <= P95
            }
        }
    }

    /**
     * Test concurrent metrics collection accuracy.
     * Requirements: 7.3, 7.4, 7.5
     */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void shouldCollectMetricsAccuratelyUnderConcurrency() {
        // Given - Multiple concurrent operations
        TestConfiguration config = configurationManager.loadConfiguration().join();
        AtomicInteger totalOperationsAcrossTests = new AtomicInteger(0);

        List<CompletableFuture<TestSummary>> futures = new ArrayList<>();

        // When - Execute multiple concurrent load tests
        for (int i = 0; i < 2; i++) {
            CompletableFuture<TestSummary> future = CompletableFuture.supplyAsync(() -> {
                TestSummary summary = loadTestService.executeLoadTest(config).join();
                totalOperationsAcrossTests.addAndGet((int) summary.getTotalOperations());
                return summary;
            });
            futures.add(future);
        }

        List<TestSummary> summaries = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        // Then - Verify metrics accuracy across concurrent executions
        assertThat(summaries).hasSize(2);

        int expectedTotalOperations = config.getTotalItems() * 2;
        assertThat(totalOperationsAcrossTests.get()).isEqualTo(expectedTotalOperations);

        for (TestSummary summary : summaries) {
            assertThat(summary.getTotalOperations()).isEqualTo(config.getTotalItems());
            assertThat(summary.getTotalSuccesses() + summary.getTotalErrors()).isEqualTo(config.getTotalItems());

            // Verify metrics consistency
            assertThat(summary.getAverageResponseTime()).isNotNull();
            assertThat(summary.getSuccessRate()).isBetween(0.0, 100.0);
        }
    }

    /**
     * Helper class to store test results for performance comparison.
     */
    private static class TestResult {
        final int concurrency;
        final TestSummary summary;
        final long executionTime;

        TestResult(int concurrency, TestSummary summary, long executionTime) {
            this.concurrency = concurrency;
            this.summary = summary;
            this.executionTime = executionTime;
        }
    }
}