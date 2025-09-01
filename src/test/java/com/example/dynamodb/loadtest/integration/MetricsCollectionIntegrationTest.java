package com.example.dynamodb.loadtest.integration;

import com.example.dynamodb.loadtest.model.TestConfiguration;
import com.example.dynamodb.loadtest.model.TestMetrics;
import com.example.dynamodb.loadtest.service.LoadTestService;
import com.example.dynamodb.loadtest.service.MetricsCollectionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for metrics collection and reporting functionality.
 */
class MetricsCollectionIntegrationTest extends LocalStackIntegrationTestBase {

    @Autowired
    private LoadTestService loadTestService;

    @Autowired
    private MetricsCollectionService metricsCollectionService;

    @AfterEach
    void cleanup() {
        cleanupTestData();
        metricsCollectionService.reset();
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void shouldCollectMetricsFromLoadTestExecution() {
        // Given
        TestConfiguration config = createTestConfiguration();
        config.setTotalItems(20);
        config.setConcurrencyLimit(3);

        // When
        var testSummary = loadTestService.executeLoadTest(config).join();

        // Then
        assertThat(testSummary).isNotNull();
        assertThat(testSummary.getTotalOperations()).isGreaterThan(0);
        assertThat(testSummary.getTotalSuccesses()).isGreaterThan(0);
        assertThat(testSummary.getTotalErrors()).isGreaterThanOrEqualTo(0);
        assertThat(testSummary.getAverageResponseTime()).isNotNull();
        assertThat(testSummary.getThroughputPerSecond()).isGreaterThan(0);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldRecordIndividualMetrics() {
        // Given
        TestMetrics metric1 = createTestMetric(Duration.ofMillis(100), true, 2);
        TestMetrics metric2 = createTestMetric(Duration.ofMillis(150), true, 3);
        TestMetrics metric3 = createTestMetric(Duration.ofMillis(200), false, 2);

        // When
        metricsCollectionService.recordMetric(metric1);
        metricsCollectionService.recordMetric(metric2);
        metricsCollectionService.recordMetric(metric3);

        // Then
        var testSummary = metricsCollectionService.generateSummary();

        assertThat(testSummary.getTotalOperations()).isEqualTo(3);
        assertThat(testSummary.getTotalSuccesses()).isEqualTo(2);
        assertThat(testSummary.getTotalErrors()).isEqualTo(1);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldCalculateAverageResponseTimeCorrectly() {
        // Given
        TestMetrics metric1 = createTestMetric(Duration.ofMillis(100), true, 1);
        TestMetrics metric2 = createTestMetric(Duration.ofMillis(200), true, 1);
        TestMetrics metric3 = createTestMetric(Duration.ofMillis(300), true, 1);

        // When
        metricsCollectionService.recordMetric(metric1);
        metricsCollectionService.recordMetric(metric2);
        metricsCollectionService.recordMetric(metric3);

        // Then
        var testSummary = metricsCollectionService.generateSummary();

        // Average should be (100 + 200 + 300) / 3 = 200ms
        assertThat(testSummary.getAverageResponseTime()).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldTrackErrorTypes() {
        // Given
        TestMetrics metricWithCapacityError = createTestMetric(Duration.ofMillis(100), false, 1);
        metricWithCapacityError.getErrorTypes().put("ProvisionedThroughputExceededException", 1);

        TestMetrics metricWithDuplicateError = createTestMetric(Duration.ofMillis(150), false, 1);
        metricWithDuplicateError.getErrorTypes().put("ConditionalCheckFailedException", 1);

        // When
        metricsCollectionService.recordMetric(metricWithCapacityError);
        metricsCollectionService.recordMetric(metricWithDuplicateError);

        // Then
        var testSummary = metricsCollectionService.generateSummary();

        assertThat(testSummary.getErrorTypeCounts()).containsKey("ProvisionedThroughputExceededException");
        assertThat(testSummary.getErrorTypeCounts()).containsKey("ConditionalCheckFailedException");
        assertThat(testSummary.getErrorTypeCounts().get("ProvisionedThroughputExceededException")).isEqualTo(1);
        assertThat(testSummary.getErrorTypeCounts().get("ConditionalCheckFailedException")).isEqualTo(1);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldHandleEmptyMetrics() {
        // When - Generate summary with no metrics
        var testSummary = metricsCollectionService.generateSummary();

        // Then
        assertThat(testSummary.getTotalOperations()).isEqualTo(0);
        assertThat(testSummary.getTotalSuccesses()).isEqualTo(0);
        assertThat(testSummary.getTotalErrors()).isEqualTo(0);
        assertThat(testSummary.getAverageResponseTime()).isEqualTo(Duration.ZERO);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldCalculateSuccessRateCorrectly() {
        // Given
        TestMetrics successMetric1 = createTestMetric(Duration.ofMillis(100), true, 1);
        TestMetrics successMetric2 = createTestMetric(Duration.ofMillis(150), true, 1);
        TestMetrics failureMetric = createTestMetric(Duration.ofMillis(200), false, 1);

        // When
        metricsCollectionService.recordMetric(successMetric1);
        metricsCollectionService.recordMetric(successMetric2);
        metricsCollectionService.recordMetric(failureMetric);

        // Then
        var testSummary = metricsCollectionService.generateSummary();

        double expectedSuccessRate = 2.0 / 3.0 * 100.0; // 2 successes out of 3 total
        double actualSuccessRate = testSummary.getSuccessRate();

        assertThat(actualSuccessRate).isCloseTo(expectedSuccessRate, org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void shouldCollectMetricsSuccessfully() {
        // Given
        TestConfiguration config = createTestConfiguration();
        config.setTotalItems(5);
        config.setConcurrencyLimit(1);

        // When
        var testSummary = loadTestService.executeLoadTest(config).join();

        // Then - Metrics collection should work correctly
        assertThat(testSummary.getTotalOperations()).isGreaterThan(0);

        // Verify metrics are collected and aggregated properly
        assertThat(testSummary.getTotalSuccesses()).isGreaterThanOrEqualTo(0);
        assertThat(testSummary.getAverageResponseTime()).isNotNull();
    }

    private TestConfiguration createTestConfiguration() {
        TestConfiguration config = new TestConfiguration();
        config.setTableName(TEST_TABLE_NAME);
        config.setConcurrencyLimit(5);
        config.setTotalItems(50);
        config.setMaxConcurrencyPercentage(80.0);
        config.setDuplicatePercentage(0.0);
        config.setCleanupAfterTest(true);
        config.setEnvironment("integration");
        return config;
    }

    private TestMetrics createTestMetric(Duration responseTime, boolean success, int concurrencyLevel) {
        TestMetrics metric = new TestMetrics();
        metric.setResponseTime(responseTime);
        metric.setSuccessCount(success ? 1 : 0);
        metric.setErrorCount(success ? 0 : 1);
        metric.setConcurrencyLevel(concurrencyLevel);
        metric.setTimestamp(Instant.now());
        return metric;
    }
}