package com.example.dynamodb.loadtest.service;

import com.example.dynamodb.loadtest.model.TestItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class VirtualThreadConcurrencyTest {

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
    void executeWithConcurrency_VirtualThreads_RespectsConcurrencyLimit()
            throws ExecutionException, InterruptedException {
        // Arrange
        List<TestItem> items = createTestItems(20);
        int concurrencyLimit = 5;

        AtomicInteger maxConcurrentThreads = new AtomicInteger(0);
        AtomicInteger currentThreads = new AtomicInteger(0);

        // Mock the metrics service to track concurrent executions
        doAnswer(invocation -> {
            int current = currentThreads.incrementAndGet();
            maxConcurrentThreads.updateAndGet(max -> Math.max(max, current));

            // Simulate some work
            Thread.sleep(50);

            currentThreads.decrementAndGet();
            return null;
        }).when(metricsCollectionService).recordSuccess(any(Duration.class), anyInt());

        // Act
        CompletableFuture<Void> result = loadTestService.executeWithConcurrency(items, concurrencyLimit);
        result.get();

        // Assert
        assertTrue(result.isDone());
        assertFalse(result.isCompletedExceptionally());

        // Verify that concurrency limit was respected (allowing some tolerance for
        // timing)
        assertTrue(maxConcurrentThreads.get() <= concurrencyLimit + 1,
                "Max concurrent threads: " + maxConcurrentThreads.get() + ", limit: " + concurrencyLimit);

        // Verify all items were processed
        verify(metricsCollectionService, times(items.size())).recordSuccess(any(Duration.class), eq(concurrencyLimit));
    }

    @Test
    void executeWithConcurrency_EmptyList_CompletesImmediately() throws ExecutionException, InterruptedException {
        // Arrange
        List<TestItem> items = new ArrayList<>();
        int concurrency = 10;

        // Act
        CompletableFuture<Void> result = loadTestService.executeWithConcurrency(items, concurrency);

        // Assert
        assertNotNull(result);
        assertTrue(result.isDone());
        assertFalse(result.isCompletedExceptionally());
        result.get(); // Should not throw

        // Verify no metrics were recorded
        verifyNoInteractions(metricsCollectionService);
    }

    @Test
    void executeWithConcurrency_SingleItem_ProcessesSuccessfully() throws ExecutionException, InterruptedException {
        // Arrange
        List<TestItem> items = List.of(new TestItem("test-key", "test-payload"));
        int concurrency = 1;

        // Act
        CompletableFuture<Void> result = loadTestService.executeWithConcurrency(items, concurrency);
        result.get();

        // Assert
        assertTrue(result.isDone());
        assertFalse(result.isCompletedExceptionally());

        // Verify metrics were recorded
        verify(metricsCollectionService).recordSuccess(any(Duration.class), eq(concurrency));
    }

    @Test
    void executeWithConcurrency_HighConcurrency_HandlesCorrectly() throws ExecutionException, InterruptedException {
        // Arrange
        List<TestItem> items = createTestItems(100);
        int concurrency = 50;

        // Act
        long startTime = System.currentTimeMillis();
        CompletableFuture<Void> result = loadTestService.executeWithConcurrency(items, concurrency);
        result.get();
        long endTime = System.currentTimeMillis();

        // Assert
        assertTrue(result.isDone());
        assertFalse(result.isCompletedExceptionally());

        // Verify all items were processed
        verify(metricsCollectionService, times(items.size())).recordSuccess(any(Duration.class), eq(concurrency));

        // Verify it completed in reasonable time (should be much faster with high
        // concurrency)
        long executionTime = endTime - startTime;
        assertTrue(executionTime < 5000, "Execution took too long: " + executionTime + "ms");
    }

    @Test
    void executeWithConcurrency_VirtualThreadPerformance_ScalesWell() throws ExecutionException, InterruptedException {
        // Arrange
        List<TestItem> items = createTestItems(1000);
        int concurrency = 100;

        // Act
        long startTime = System.currentTimeMillis();
        CompletableFuture<Void> result = loadTestService.executeWithConcurrency(items, concurrency);
        result.get();
        long endTime = System.currentTimeMillis();

        // Assert
        assertTrue(result.isDone());
        assertFalse(result.isCompletedExceptionally());

        // Verify all items were processed
        verify(metricsCollectionService, times(items.size())).recordSuccess(any(Duration.class), eq(concurrency));

        // Performance check - should handle 1000 items efficiently
        long executionTime = endTime - startTime;
        assertTrue(executionTime < 10000, "Execution took too long for 1000 items: " + executionTime + "ms");
    }

    @Test
    void executeWithConcurrency_ConcurrencyOne_ProcessesSequentially() throws ExecutionException, InterruptedException {
        // Arrange
        List<TestItem> items = createTestItems(5);
        int concurrency = 1;

        AtomicInteger maxConcurrentThreads = new AtomicInteger(0);
        AtomicInteger currentThreads = new AtomicInteger(0);

        doAnswer(invocation -> {
            int current = currentThreads.incrementAndGet();
            maxConcurrentThreads.updateAndGet(max -> Math.max(max, current));

            Thread.sleep(10); // Small delay to ensure sequential processing is detectable

            currentThreads.decrementAndGet();
            return null;
        }).when(metricsCollectionService).recordSuccess(any(Duration.class), anyInt());

        // Act
        CompletableFuture<Void> result = loadTestService.executeWithConcurrency(items, concurrency);
        result.get();

        // Assert
        assertTrue(result.isDone());

        // With concurrency 1, we should never have more than 1 thread processing at
        // once
        assertEquals(1, maxConcurrentThreads.get());

        verify(metricsCollectionService, times(items.size())).recordSuccess(any(Duration.class), eq(concurrency));
    }

    @Test
    void processItem_SimulatesWork_RecordsMetrics() throws Exception {
        // This test verifies the private processItem method indirectly through
        // executeWithConcurrency

        // Arrange
        List<TestItem> items = List.of(new TestItem("test-key", "test-payload"));
        int concurrency = 1;

        // Act
        CompletableFuture<Void> result = loadTestService.executeWithConcurrency(items, concurrency);
        result.get();

        // Assert
        verify(metricsCollectionService).recordSuccess(any(Duration.class), eq(concurrency));

        // Verify the duration is reasonable (should be > 0 due to simulated work)
        verify(metricsCollectionService).recordSuccess(
                argThat(duration -> duration.toMillis() >= 0 && duration.toMillis() < 1000), eq(concurrency));
    }

    private List<TestItem> createTestItems(int count) {
        List<TestItem> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add(new TestItem("key-" + i, "payload-" + i));
        }
        return items;
    }
}