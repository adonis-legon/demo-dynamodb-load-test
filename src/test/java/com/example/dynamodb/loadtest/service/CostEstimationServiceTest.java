package com.example.dynamodb.loadtest.service;

import com.example.dynamodb.loadtest.service.MetricsCollectionService.TestSummary;
import com.example.dynamodb.loadtest.service.CostEstimationService.CostEstimate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class CostEstimationServiceTest {

    private CostEstimationService costEstimationService;

    @BeforeEach
    void setUp() {
        costEstimationService = new CostEstimationService();
        // Set test values for CPU and memory
        ReflectionTestUtils.setField(costEstimationService, "taskCpu", 4096); // 4 vCPUs
        ReflectionTestUtils.setField(costEstimationService, "taskMemory", 8192); // 8GB
    }

    @Test
    void testCalculateCostEstimate() {
        // Arrange
        TestSummary summary = createTestSummary(10000, 10000, Duration.ofMinutes(5));

        // Act
        CostEstimate estimate = costEstimationService.calculateCostEstimate(summary);

        // Assert
        assertNotNull(estimate);
        assertTrue(estimate.totalCost.compareTo(BigDecimal.ZERO) > 0);
        assertTrue(estimate.dynamoDbTotalCost.compareTo(BigDecimal.ZERO) > 0);
        assertTrue(estimate.ecsComputeCost.compareTo(BigDecimal.ZERO) > 0);
        assertTrue(estimate.costPerOperation.compareTo(BigDecimal.ZERO) > 0);
        assertTrue(estimate.costPerHour.compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void testDynamoDbCostCalculation() {
        // Arrange - 100K successful operations
        TestSummary summary = createTestSummary(100000, 100000, Duration.ofMinutes(10));

        // Act
        CostEstimate estimate = costEstimationService.calculateCostEstimate(summary);

        // Assert
        // 100K writes should cost approximately $0.125 (100K * $1.25/million)
        assertTrue(estimate.dynamoDbWriteCost.compareTo(new BigDecimal("0.10")) > 0);
        assertTrue(estimate.dynamoDbWriteCost.compareTo(new BigDecimal("0.15")) < 0);
    }

    @Test
    void testEcsCostCalculation() {
        // Arrange - 1 hour test
        TestSummary summary = createTestSummary(1000, 1000, Duration.ofHours(1));

        // Act
        CostEstimate estimate = costEstimationService.calculateCostEstimate(summary);

        // Assert
        // 4 vCPUs for 1 hour should cost approximately $0.16 (4 * $0.04048)
        // 8GB RAM for 1 hour should cost approximately $0.036 (8 * $0.004445)
        assertTrue(estimate.ecsComputeCost.compareTo(new BigDecimal("0.15")) > 0);
        assertTrue(estimate.ecsComputeCost.compareTo(new BigDecimal("0.25")) < 0);
    }

    @Test
    void testCostFormatting() {
        // Arrange
        CostEstimate estimate = new CostEstimate();

        // Test small amounts (show 4 decimal places)
        String smallAmount = estimate.formatCurrency(new BigDecimal("0.0045"));
        assertEquals("$0.0045", smallAmount);

        // Test larger amounts (show 2 decimal places)
        String largeAmount = estimate.formatCurrency(new BigDecimal("1.234"));
        assertEquals("$1.23", largeAmount);
    }

    @Test
    void testZeroCostHandling() {
        // Arrange
        TestSummary summary = createTestSummary(0, 0, Duration.ZERO);

        // Act
        CostEstimate estimate = costEstimationService.calculateCostEstimate(summary);

        // Assert
        assertEquals(0, estimate.dynamoDbWriteCost.compareTo(BigDecimal.ZERO));
        assertEquals(0, estimate.ecsComputeCost.compareTo(BigDecimal.ZERO));
        assertEquals(0, estimate.totalCost.compareTo(BigDecimal.ZERO));
    }

    private TestSummary createTestSummary(long totalOps, long successfulOps, Duration duration) {
        Instant endTime = Instant.now();
        Instant startTime = endTime.minus(duration);

        return new TestSummary(
                totalOps, // totalOperations
                successfulOps, // totalSuccesses
                totalOps - successfulOps, // totalErrors
                new java.util.HashMap<>(), // errorTypeCounts
                duration, // testDuration
                startTime, // testStartTime
                endTime, // testEndTime
                new java.util.HashMap<>(), // concurrencyLevelMetrics
                new java.util.HashMap<>(), // responseTimePercentiles
                Duration.ofMillis(100), // averageResponseTime
                totalOps / Math.max(1, duration.getSeconds()) // throughputPerSecond
        );
    }
}