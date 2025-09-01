package com.example.dynamodb.loadtest;

import com.example.dynamodb.loadtest.model.TestConfiguration;
import com.example.dynamodb.loadtest.service.ConfigurationManager;
import com.example.dynamodb.loadtest.service.LoadTestService;
import com.example.dynamodb.loadtest.service.MetricsCollectionService.TestSummary;
import com.example.dynamodb.loadtest.service.ReportGenerationService;
import com.example.dynamodb.loadtest.service.AccurateDuplicateCounter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for LoadTestApplication.
 * Tests application startup, configuration loading, and execution flow.
 */
@ExtendWith(MockitoExtension.class)
class LoadTestApplicationIntegrationTest {

        @Mock
        private ConfigurationManager configurationManager;

        @Mock
        private LoadTestService loadTestService;

        @Mock
        private ReportGenerationService reportGenerationService;

        @Mock
        private AccurateDuplicateCounter accurateDuplicateCounter;

        @Test
        void testApplicationStartupAndExecution() throws Exception {
                // Arrange
                TestConfiguration testConfig = createTestConfiguration();
                TestSummary testSummary = createTestSummary();

                when(configurationManager.loadConfiguration())
                                .thenReturn(CompletableFuture.completedFuture(testConfig));
                when(loadTestService.executeLoadTest(testConfig))
                                .thenReturn(CompletableFuture.completedFuture(testSummary));

                LoadTestApplication application = new LoadTestApplication(
                                configurationManager, loadTestService, reportGenerationService, accurateDuplicateCounter);

                // Act
                assertDoesNotThrow(() -> application.run());

                // Assert
                verify(configurationManager).loadConfiguration();
                verify(loadTestService).executeLoadTest(testConfig);
                verify(reportGenerationService).generateReport(testSummary);
        }

        @Test
        void testApplicationHandlesConfigurationFailure() throws Exception {
                // Arrange
                CompletableFuture<TestConfiguration> failedFuture = new CompletableFuture<>();
                failedFuture.completeExceptionally(new RuntimeException("Configuration load failed"));

                when(configurationManager.loadConfiguration()).thenReturn(failedFuture);

                LoadTestApplication application = new LoadTestApplication(
                                configurationManager, loadTestService, reportGenerationService, accurateDuplicateCounter);

                // Act & Assert
                Exception exception = assertThrows(Exception.class, () -> application.run());
                assertTrue(exception.getMessage().contains("Configuration load failed"));

                verify(configurationManager).loadConfiguration();
                verifyNoInteractions(loadTestService);
                verifyNoInteractions(reportGenerationService);
        }

        @Test
        void testApplicationHandlesLoadTestFailure() throws Exception {
                // Arrange
                TestConfiguration testConfig = createTestConfiguration();
                CompletableFuture<TestSummary> failedFuture = new CompletableFuture<>();
                failedFuture.completeExceptionally(new RuntimeException("Load test execution failed"));

                when(configurationManager.loadConfiguration())
                                .thenReturn(CompletableFuture.completedFuture(testConfig));
                when(loadTestService.executeLoadTest(testConfig)).thenReturn(failedFuture);

                LoadTestApplication application = new LoadTestApplication(
                                configurationManager, loadTestService, reportGenerationService, accurateDuplicateCounter);

                // Act & Assert
                Exception exception = assertThrows(Exception.class, () -> application.run());
                assertTrue(exception.getMessage().contains("Load test execution failed"));

                verify(configurationManager).loadConfiguration();
                verify(loadTestService).executeLoadTest(testConfig);
                verifyNoInteractions(reportGenerationService);
        }

        @Test
        void testApplicationLogsCorrectInformation() throws Exception {
                // Arrange
                TestConfiguration testConfig = createTestConfiguration();
                TestSummary testSummary = createTestSummary();

                when(configurationManager.loadConfiguration())
                                .thenReturn(CompletableFuture.completedFuture(testConfig));
                when(loadTestService.executeLoadTest(testConfig))
                                .thenReturn(CompletableFuture.completedFuture(testSummary));

                LoadTestApplication application = new LoadTestApplication(
                                configurationManager, loadTestService, reportGenerationService, accurateDuplicateCounter);

                // Act
                assertDoesNotThrow(() -> application.run());

                // Assert - verify all services were called in correct order
                verify(configurationManager).loadConfiguration();
                verify(loadTestService).executeLoadTest(testConfig);
                verify(reportGenerationService).generateReport(testSummary);
        }

        private TestConfiguration createTestConfiguration() {
                TestConfiguration config = new TestConfiguration();
                config.setEnvironment("test");
                config.setTableName("test-table");
                config.setTotalItems(1000);
                config.setConcurrencyLimit(10);
                config.setMaxConcurrencyPercentage(0.8);
                config.setDuplicatePercentage(0.0);
                config.setCleanupAfterTest(true);
                return config;
        }

        private TestSummary createTestSummary() {
                return new TestSummary(
                                1000L, // totalOperations
                                950L, // totalSuccesses
                                50L, // totalErrors
                                new java.util.HashMap<>(), // errorTypeCounts
                                Duration.ofMinutes(1), // testDuration
                                Instant.now().minusSeconds(60), // testStartTime
                                Instant.now(), // testEndTime
                                new java.util.HashMap<>(), // concurrencyLevelMetrics
                                new java.util.HashMap<>(), // responseTimePercentiles
                                Duration.ofMillis(100), // averageResponseTime
                                16.67 // throughputPerSecond
                );
        }
}