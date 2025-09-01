package com.example.dynamodb.loadtest.service;

import com.example.dynamodb.loadtest.model.TestConfiguration;
import com.example.dynamodb.loadtest.service.MetricsCollectionService.TestSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ProgressiveLoadTestTest {

        @Mock
        private MetricsCollectionService metricsCollectionService;

        @Mock
        private ResilientDynamoDBService resilientDynamoDBService;

        @Mock
        private AccurateDuplicateCounter accurateDuplicateCounter;

        private LoadTestServiceImpl loadTestService;
        private Executor virtualThreadExecutor;

        @BeforeEach
        void setUp() {
                virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

                // Mock the ResilientDynamoDBService to return successful CompletableFuture
                // (lenient for tests that don't use it)
                lenient().when(resilientDynamoDBService.putItemWithResilience(any(), any()))
                                .thenReturn(CompletableFuture.completedFuture(null));
                lenient().when(resilientDynamoDBService.putItemWithEnhancedResilience(any(), any()))
                                .thenReturn(CompletableFuture.completedFuture(null));

                loadTestService = new LoadTestServiceImpl(metricsCollectionService, resilientDynamoDBService,
                                accurateDuplicateCounter, virtualThreadExecutor);
                loadTestService.resetKeyCounter();
        }

        @Test
        void executeLoadTest_ProgressiveRamping_ExecutesCorrectly() throws ExecutionException, InterruptedException {
                // Arrange
                TestConfiguration config = new TestConfiguration(
                                "test-table", 10, 100, 50.0, 0.0, false, "test");

                TestSummary mockSummary = new TestSummary(
                                100, 95, 5,
                                Map.of("ProcessingError", 5L),
                                Duration.ofSeconds(5),
                                Instant.now().minusSeconds(5),
                                Instant.now(),
                                Map.of(),
                                Map.of(),
                                Duration.ofMillis(50),
                                20.0);

                when(metricsCollectionService.generateSummary()).thenReturn(mockSummary);

                // Act
                CompletableFuture<TestSummary> result = loadTestService.executeLoadTest(config);
                TestSummary summary = result.get();

                // Assert
                assertNotNull(summary);
                assertEquals(mockSummary, summary);

                // Verify metrics service interactions
                verify(metricsCollectionService).startTest();
                verify(metricsCollectionService).endTest();
                verify(metricsCollectionService).generateSummary();

                // Verify that metrics were recorded (should have multiple calls due to
                // progressive ramping)
                verify(metricsCollectionService, atLeastOnce()).recordSuccess(any(Duration.class), anyInt());
        }

        @Test
        void executeLoadTest_WithDuplicates_HandlesCorrectly() throws ExecutionException, InterruptedException {
                // Arrange
                TestConfiguration config = new TestConfiguration(
                                "test-table", 5, 50, 80.0, 25.0, false, "test");

                TestSummary mockSummary = new TestSummary(
                                50, 45, 5,
                                Map.of("ProcessingError", 5L),
                                Duration.ofSeconds(3),
                                Instant.now().minusSeconds(3),
                                Instant.now(),
                                Map.of(),
                                Map.of(),
                                Duration.ofMillis(30),
                                16.7);

                when(metricsCollectionService.generateSummary()).thenReturn(mockSummary);

                // Act
                CompletableFuture<TestSummary> result = loadTestService.executeLoadTest(config);
                TestSummary summary = result.get();

                // Assert
                assertNotNull(summary);
                assertEquals(mockSummary, summary);

                // Verify that the test completed successfully
                assertTrue(result.isDone());
                assertFalse(result.isCompletedExceptionally());
        }

        @Test
        void executeLoadTest_SmallConfiguration_ExecutesEfficiently() throws ExecutionException, InterruptedException {
                // Arrange
                TestConfiguration config = new TestConfiguration(
                                "test-table", 2, 10, 60.0, 0.0, false, "test");

                TestSummary mockSummary = new TestSummary(
                                10, 10, 0,
                                Map.of(),
                                Duration.ofSeconds(1),
                                Instant.now().minusSeconds(1),
                                Instant.now(),
                                Map.of(),
                                Map.of(),
                                Duration.ofMillis(20),
                                10.0);

                when(metricsCollectionService.generateSummary()).thenReturn(mockSummary);

                // Act
                long startTime = System.currentTimeMillis();
                CompletableFuture<TestSummary> result = loadTestService.executeLoadTest(config);
                TestSummary summary = result.get();
                long endTime = System.currentTimeMillis();

                // Assert
                assertNotNull(summary);
                assertEquals(mockSummary, summary);

                // Verify execution time is reasonable for small test
                long executionTime = endTime - startTime;
                assertTrue(executionTime < 5000, "Small test took too long: " + executionTime + "ms");
        }

        @Test
        void executeLoadTest_LargeConfiguration_ScalesWell() throws ExecutionException, InterruptedException {
                // Arrange
                TestConfiguration config = new TestConfiguration(
                                "test-table", 50, 500, 70.0, 30.0, false, "test");

                TestSummary mockSummary = new TestSummary(
                                500, 480, 20,
                                Map.of("ProcessingError", 20L),
                                Duration.ofSeconds(10),
                                Instant.now().minusSeconds(10),
                                Instant.now(),
                                Map.of(),
                                Map.of(),
                                Duration.ofMillis(40),
                                50.0);

                when(metricsCollectionService.generateSummary()).thenReturn(mockSummary);

                // Act
                CompletableFuture<TestSummary> result = loadTestService.executeLoadTest(config);
                TestSummary summary = result.get();

                // Assert
                assertNotNull(summary);
                assertEquals(mockSummary, summary);

                // Verify that many operations were recorded
                verify(metricsCollectionService, atLeast(400)).recordSuccess(any(Duration.class), anyInt());
        }

        @Test
        void executeLoadTest_HighConcurrencyPercentage_HandlesCorrectly()
                        throws ExecutionException, InterruptedException {
                // Arrange - 90% of items at max concurrency
                TestConfiguration config = new TestConfiguration(
                                "test-table", 20, 100, 90.0, 0.0, false, "test");

                TestSummary mockSummary = new TestSummary(
                                100, 98, 2,
                                Map.of("ProcessingError", 2L),
                                Duration.ofSeconds(4),
                                Instant.now().minusSeconds(4),
                                Instant.now(),
                                Map.of(),
                                Map.of(),
                                Duration.ofMillis(25),
                                25.0);

                when(metricsCollectionService.generateSummary()).thenReturn(mockSummary);

                // Act
                CompletableFuture<TestSummary> result = loadTestService.executeLoadTest(config);
                TestSummary summary = result.get();

                // Assert
                assertNotNull(summary);
                assertEquals(mockSummary, summary);

                // Verify that most operations were recorded at high concurrency
                verify(metricsCollectionService, atLeast(90)).recordSuccess(any(Duration.class), anyInt());
        }

        @Test
        void executeLoadTest_LowConcurrencyPercentage_HandlesCorrectly()
                        throws ExecutionException, InterruptedException {
                // Arrange - Only 10% of items at max concurrency
                TestConfiguration config = new TestConfiguration(
                                "test-table", 15, 100, 10.0, 0.0, false, "test");

                TestSummary mockSummary = new TestSummary(
                                100, 95, 5,
                                Map.of("ProcessingError", 5L),
                                Duration.ofSeconds(6),
                                Instant.now().minusSeconds(6),
                                Instant.now(),
                                Map.of(),
                                Map.of(),
                                Duration.ofMillis(35),
                                16.7);

                when(metricsCollectionService.generateSummary()).thenReturn(mockSummary);

                // Act
                CompletableFuture<TestSummary> result = loadTestService.executeLoadTest(config);
                TestSummary summary = result.get();

                // Assert
                assertNotNull(summary);
                assertEquals(mockSummary, summary);

                // Most operations should be in ramp-up phase with lower concurrency
                verify(metricsCollectionService, atLeast(90)).recordSuccess(any(Duration.class), anyInt());
        }

        @Test
        void executeLoadTest_ExceptionDuringExecution_HandlesGracefully() {
                // Arrange
                TestConfiguration config = new TestConfiguration(
                                "test-table", 5, 20, 50.0, 0.0, false, "test");

                // Simulate an exception during metrics collection
                doThrow(new RuntimeException("Metrics error")).when(metricsCollectionService).startTest();

                // Act & Assert
                CompletableFuture<TestSummary> result = loadTestService.executeLoadTest(config);

                ExecutionException exception = assertThrows(ExecutionException.class, result::get);
                assertTrue(exception.getCause() instanceof RuntimeException);
                assertTrue(exception.getCause().getMessage().contains("Load test execution failed"));

                // Verify that endTest is still called even on error
                verify(metricsCollectionService).endTest();
        }

        @Test
        void executeLoadTest_ValidatesConfiguration_CalculatesCorrectly()
                        throws ExecutionException, InterruptedException {
                // Arrange
                TestConfiguration config = new TestConfiguration(
                                "test-table", 8, 80, 25.0, 20.0, false, "test");

                // Verify configuration calculations
                assertEquals(8, config.getConcurrencyLimit());
                assertEquals(80, config.getTotalItems());
                assertEquals(25.0, config.getMaxConcurrencyPercentage());
                assertEquals(2, config.getMaxConcurrencyLevel()); // 8 * 0.25 = 2
                assertEquals(20, config.getItemsForMaxConcurrency()); // 80 * 0.25 = 20
                assertEquals(60, config.getItemsForRampUp()); // 80 - 20 = 60

                TestSummary mockSummary = new TestSummary(
                                80, 75, 5,
                                Map.of("ProcessingError", 5L),
                                Duration.ofSeconds(4),
                                Instant.now().minusSeconds(4),
                                Instant.now(),
                                Map.of(),
                                Map.of(),
                                Duration.ofMillis(30),
                                20.0);

                when(metricsCollectionService.generateSummary()).thenReturn(mockSummary);

                // Act
                CompletableFuture<TestSummary> result = loadTestService.executeLoadTest(config);
                TestSummary summary = result.get();

                // Assert
                assertNotNull(summary);
                assertEquals(mockSummary, summary);
        }
}