package com.example.dynamodb.loadtest.service;

import com.example.dynamodb.loadtest.service.MetricsCollectionService.TestSummary;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class ReportGenerationServiceSimpleTest {

    @Test
    void testReportGenerationService_BasicFunctionality() {
        // Given
        ReportGenerationService service = new ReportGenerationService();

        TestSummary summary = new TestSummary(
                100L, 90L, 10L, new HashMap<>(),
                Duration.ofMinutes(1),
                Instant.now().minusSeconds(60),
                Instant.now(),
                new HashMap<>(),
                new HashMap<>(),
                Duration.ofMillis(50),
                100.0);

        // When
        String result = service.generateSummaryReport(summary);

        // Then
        assertNotNull(result);
        assertTrue(result.contains("Load Test Summary:"));
        assertTrue(result.contains("Total Operations: 100"));
        assertTrue(result.contains("Success Rate: 90.00%"));
        assertTrue(result.contains("Error Rate: 10.00%"));
    }

    @Test
    void testReportGenerationService_NullHandling() {
        // Given
        ReportGenerationService service = new ReportGenerationService();

        // When
        String result = service.generateSummaryReport(null);

        // Then
        assertEquals("No test summary available", result);
    }
}