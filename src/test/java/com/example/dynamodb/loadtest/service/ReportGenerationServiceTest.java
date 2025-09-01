package com.example.dynamodb.loadtest.service;

import com.example.dynamodb.loadtest.model.TestMetrics;
import com.example.dynamodb.loadtest.service.MetricsCollectionService.TestSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReportGenerationServiceTest {

    private ReportGenerationService reportService;
    private ByteArrayOutputStream outputStream;
    private PrintStream printStream;

    @BeforeEach
    void setUp() {
        reportService = new ReportGenerationService();
        outputStream = new ByteArrayOutputStream();
        printStream = new PrintStream(outputStream);
    }

    @Test
    void testGenerateReport_WithValidSummary_ShouldGenerateCompleteReport() {
        // Given
        TestSummary summary = createTestSummary();

        // When
        reportService.generateReport(summary, printStream);

        // Then
        String output = outputStream.toString();

        // Verify header
        assertTrue(output.contains("DYNAMODB LOAD TEST REPORT"));
        assertTrue(output.contains("=".repeat(80)));

        // Verify test overview section
        assertTrue(output.contains("TEST OVERVIEW"));
        assertTrue(output.contains("Duration:"));
        assertTrue(output.contains("Total Operations:  1,000"));
        assertTrue(output.contains("Successful Ops:    900"));
        assertTrue(output.contains("Failed Ops:        100"));

        // Verify performance metrics section
        assertTrue(output.contains("PERFORMANCE METRICS"));
        assertTrue(output.contains("Success Rate:      90.00%"));
        assertTrue(output.contains("Error Rate:        10.00%"));
        assertTrue(output.contains("Throughput:"));
        assertTrue(output.contains("Response Time Statistics:"));
        assertTrue(output.contains("Average:"));

        // Verify error analysis section
        assertTrue(output.contains("ERROR ANALYSIS"));
        assertTrue(output.contains("Total Errors:      100"));
        assertTrue(output.contains("Error Breakdown:"));

        // Verify concurrency analysis section
        assertTrue(output.contains("CONCURRENCY ANALYSIS"));
        assertTrue(output.contains("Concurrency"));
        assertTrue(output.contains("Operations"));
        assertTrue(output.contains("Success Rate"));

        // Verify footer
        assertTrue(output.contains("Report generated at:"));
    }

    @Test
    void testGenerateReport_WithNullSummary_ShouldHandleGracefully() {
        // When
        reportService.generateReport(null, printStream);

        // Then
        String output = outputStream.toString();
        assertTrue(output.contains("Error: No test summary available for report generation"));
    }

    @Test
    void testGenerateSummaryReport_WithValidSummary_ShouldReturnFormattedSummary() {
        // Given
        TestSummary summary = createTestSummary();

        // When
        String result = reportService.generateSummaryReport(summary);

        // Then
        assertTrue(result.contains("Load Test Summary:"));
        assertTrue(result.contains("Total Operations: 1,000"));
        assertTrue(result.contains("Success Rate: 90.00%"));
        assertTrue(result.contains("Error Rate: 10.00%"));
        assertTrue(result.contains("Average Response Time:"));
        assertTrue(result.contains("Throughput:"));
        assertTrue(result.contains("Test Duration:"));
    }

    @Test
    void testGenerateSummaryReport_WithNullSummary_ShouldReturnErrorMessage() {
        // When
        String result = reportService.generateSummaryReport(null);

        // Then
        assertEquals("No test summary available", result);
    }

    // Helper methods to create test data

    private TestSummary createTestSummary() {
        Map<String, Long> errorCounts = new HashMap<>();
        errorCounts.put(TestMetrics.ERROR_TYPE_CAPACITY_EXCEEDED, 50L);
        errorCounts.put(TestMetrics.ERROR_TYPE_DUPLICATE_KEY, 30L);
        errorCounts.put(TestMetrics.ERROR_TYPE_NETWORK, 20L);

        Map<Integer, TestMetrics> concurrencyMetrics = new HashMap<>();
        concurrencyMetrics.put(1, new TestMetrics(Duration.ofMillis(100), 100, 10, 1));
        concurrencyMetrics.put(5, new TestMetrics(Duration.ofMillis(150), 200, 20, 5));

        Map<String, Duration> percentiles = new HashMap<>();
        percentiles.put("p50", Duration.ofMillis(100));
        percentiles.put("p90", Duration.ofMillis(200));
        percentiles.put("p95", Duration.ofMillis(250));
        percentiles.put("p99", Duration.ofMillis(300));

        return new TestSummary(
                1000L, 900L, 100L, errorCounts,
                Duration.ofMinutes(5),
                Instant.now().minusSeconds(300),
                Instant.now(),
                concurrencyMetrics,
                percentiles,
                Duration.ofMillis(120),
                200.0);
    }
}